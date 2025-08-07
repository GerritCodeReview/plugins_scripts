// Copyright (C) 2024 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import com.google.common.cache.*
import com.google.common.flogger.*
import com.google.gerrit.common.*
import com.google.gerrit.entities.*
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.extensions.events.*
import com.google.gerrit.extensions.registration.*
import com.google.gerrit.server.query.group.*
import com.google.gerrit.metrics.*
import com.google.gerrit.lifecycle.*
import com.google.gerrit.server.*
import com.google.gerrit.server.account.*
import com.google.gerrit.server.cache.*
import com.google.gerrit.server.config.*
import com.google.gerrit.server.project.*
import com.google.inject.*
import com.google.inject.name.*

import java.time.*
import java.util.function.*
import java.util.stream.Collectors

import static java.util.concurrent.TimeUnit.*

@Singleton
class ActiveUsersMetric {

  static final NAME = "active_users"
  static final DESCRIPTION = "Number of active users"

  @Inject
  public ActiveUsersMetric(MetricMaker metrics, @Named(TrackActiveUsersCache.NAME) Cache<Integer, Long> trackActiveUsersCache){
    metrics.newCallbackMetric(
      NAME,
      Long.class,
      new Description(DESCRIPTION).setGauge().setUnit("users"),
      { -> trackActiveUsersCache.size() }
    );
  }
}

class TrackActiveUsersCache extends CacheModule {
  static final NAME = "users_cache"
  static final DEFAULT_CACHE_TTL = Duration.ofDays(90)
  static final MAX_SIZE = 300_000

  @Override
  protected void configure() {
    persist(NAME, Integer, Long)
        .diskLimit(MAX_SIZE)
        .maximumWeight(MAX_SIZE)
        .expireAfterWrite(DEFAULT_CACHE_TTL)
  }
}

@Singleton
class TrackingGroupBackend implements GroupBackend {
  @Inject
  @Named(TrackActiveUsersCache.NAME)
  Cache<Integer, Long> trackActiveUsersCache

  @Override
  boolean handles(AccountGroup.UUID uuid) {
    return true
  }

  @Override
  GroupDescription.Basic get(AccountGroup.UUID uuid) {
    return null
  }

  @Override
  Collection<GroupReference> suggest(String name, @Nullable ProjectState project) {
    return Collections.emptyList()
  }

  @Override
  GroupMembership membershipsOf(CurrentUser user) {
    if (user.identifiedUser) {
      def accountId = user.accountId.get()
      def currentMinutes = MILLISECONDS.toMinutes(System.currentTimeMillis())
      if (trackActiveUsersCache.getIfPresent(accountId) != currentMinutes) {
        trackActiveUsersCache.put(accountId, currentMinutes)
      }
    }
    return GroupMembership.EMPTY
  }

  @Override
  boolean isVisibleToAll(AccountGroup.UUID uuid) {
    return false
  }
}

@Singleton
class AutoDisableInactiveUsersConfig {
  static final FluentLogger logger = FluentLogger.forEnclosingClass()

  final Set<Account.Id> ignoreAccountIds
  final Set<AccountGroup.UUID> ignoreGroupIds

  private final PluginConfig config

  @Inject
  AutoDisableInactiveUsersConfig(
      PluginConfigFactory configFactory,
      GroupCache groupCache,
      InternalGroupBackend internalGroupBackend,
      IdentifiedUser.GenericFactory userFactory,
      Accounts accounts,
      @PluginName String pluginName
  ) {
    config = configFactory.getFromGerritConfig(pluginName)

    ignoreAccountIds = ignoreAccountIdsFromConfig("ignoreAccountId")
    ignoreGroupIds = ignoreGroupIdsFromConfig("ignoreGroup", groupCache)

    logger.atInfo().log("Accounts ids ignored for inactivity: %s", ignoreAccountIds)
    logger.atInfo().log("Group ids ignored for inactivity: %s", ignoreGroupIds)

    def impliedAccountIds = accounts.all().findAll {
      internalGroupBackend.membershipsOf(userFactory.create(it.account().id())).containsAnyOf(ignoreGroupIds)
    }.collect { it.account.id() }

    logger.atInfo().log("Implied accounts ids ignored for inactivity: %s", impliedAccountIds)
    ignoreAccountIds += impliedAccountIds

    logger.atInfo().log("Full list of accounts ids ignored for inactivity: %s", ignoreAccountIds)
  }

  private Set<Account.Id> ignoreAccountIdsFromConfig(String name) {
    def strings = config.getStringList(name) as Set
    strings.stream()
        .map(Account.Id.&tryParse)
        .filter { it.isPresent() }
        .map { it.get() }
        .collect(Collectors.toSet())
  }

  private Set<AccountGroup.UUID> ignoreGroupIdsFromConfig(String name, GroupCache groupCache) {
    def groups = config.getStringList(name)
    def groupNames = groups.collect { AccountGroup.nameKey(it)}
    groupNames
        .collect { groupCache.get(it) }
        .findAll { it.present }
        .collect { it.get().groupUUID }
        .toSet()
  }
}

class AutoDisableInactiveUsersEvictionListener implements CacheRemovalListener<Integer, Long> {
  static final FluentLogger logger = FluentLogger.forEnclosingClass()

  private final String pluginName
  private final String fullCacheName
  private final Cache<Integer, Long> trackActiveUsersCache
  private final Provider<AccountsUpdate> accountsUpdate
  private final AutoDisableInactiveUsersConfig autoDisableConfig

  @Inject
  AutoDisableInactiveUsersEvictionListener(
      @PluginName String pluginName,
      @ServerInitiated Provider<AccountsUpdate> accountsUpdate,
      @Named(TrackActiveUsersCache.NAME) Cache<Integer, Long> trackActiveUsersCache,
      AutoDisableInactiveUsersConfig autoDisableConfig
  ) {
    this.pluginName = pluginName
    this.accountsUpdate = accountsUpdate
    this.autoDisableConfig = autoDisableConfig
    this.trackActiveUsersCache = trackActiveUsersCache
    fullCacheName = "${pluginName}.${TrackActiveUsersCache.NAME}"
  }

  @Override
  void onRemoval(String pluginName, String cacheName, RemovalNotification<Integer, Long> notification) {
    if (fullCacheName != cacheName) {
      return
    }

    if (notification.cause == RemovalCause.EXPIRED) {
      disableAccount(Account.id(notification.key))
    } else if (notification.cause == RemovalCause.EXPLICIT) {
      logger.atWarning().log(
          "cache %s do not support eviction, entry for user %d will be added back", fullCacheName, notification.key)
      trackActiveUsersCache.put(notification.key, notification.value)
    }
  }

  private void disableAccount(Account.Id accountId) {
    if (autoDisableConfig.ignoreAccountIds.contains(accountId)) {
      return
    }

    logger.atInfo().log("Automatically disabling user id: %d", accountId.get())
    accountsUpdate.get().update(
        """Automatically disabling after inactivity

Disabled by ${pluginName}""",
        accountId,
        new Consumer<AccountDelta.Builder>() {
          @Override
          void accept(AccountDelta.Builder builder) {
            builder.setActive(false)
          }
        })
  }
}

@Singleton
class AutoDisableInactiveUsersListener implements LifecycleListener {
  static final FluentLogger logger = FluentLogger.forEnclosingClass()

  @Inject
  Accounts accounts

  @Inject
  ServiceUserClassifier serviceUserClassifier

  @Inject
  @Named(TrackActiveUsersCache.NAME)
  Cache<Integer, Long> trackActiveUsersCache

  @Inject
  AutoDisableInactiveUsersConfig autoDisableConfig

  @Override
  void start() {
    def currentMinutes = MILLISECONDS.toMinutes(System.currentTimeMillis())
    accounts.all()
        .findAll {
          it.account().isActive() && !serviceUserClassifier.isServiceUser(it.account().id()) && !trackActiveUsersCache.getIfPresent(it.account().id().get())
        }
        .each { trackActiveUsersCache.put(it.account().id().get(), currentMinutes) }
  }

  @Override
  void stop() {
    // no-op
  }
}

class TrackAndDisableInactiveUsersModule extends LifecycleModule {
  @Override
  void configure() {
    install(new TrackActiveUsersCache())
    bind(ActiveUsersMetric.class).asEagerSingleton()
    listener().to(AutoDisableInactiveUsersListener)
    DynamicSet.bind(binder(), CacheRemovalListener).to(AutoDisableInactiveUsersEvictionListener)

    DynamicSet.bind(binder(), GroupBackend).to(TrackingGroupBackend)
  }
}

modules = [TrackAndDisableInactiveUsersModule]
