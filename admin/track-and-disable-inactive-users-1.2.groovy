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
import com.google.gerrit.extensions.annotations.PluginName
import com.google.gerrit.extensions.annotations.PluginData
import com.google.gerrit.extensions.events.*
import com.google.gerrit.extensions.registration.DynamicSet
import com.google.gerrit.lifecycle.*
import com.google.gerrit.server.*
import com.google.gerrit.server.account.*
import com.google.gerrit.server.cache.*
import com.google.gerrit.server.config.*
import com.google.gerrit.server.git.*
import com.google.gerrit.server.project.*
import com.google.inject.*
import com.google.inject.name.*
import org.eclipse.jgit.lib.*

import java.nio.file.*
import java.time.*
import java.util.concurrent.*
import java.util.function.*
import java.util.stream.Collectors

import static java.util.concurrent.TimeUnit.*

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

class AutoDisableInactiveUsersConfig {
  final long gracePeriodEnd
  final Duration inactivityCutOff
  final long autoDisableInterval
  final Set<Account.Id> ignoreAccountIds

  private final PluginConfig config

  @Inject
  AutoDisableInactiveUsersConfig(
      PluginConfigFactory configFactory,
      @PluginName String pluginName,
      @PluginData Path pluginDataPath,
      @GerritServerConfig Config gerritConfig) {
    config = configFactory.getFromGerritConfig(pluginName)

    def scriptInstallDate = getScriptInstallDate(pluginDataPath)
    def cacheTtl = gerritConfig.getTimeUnit(
        "cache",
        "${pluginName}.${TrackActiveUsersCache.NAME}",
        "maxAge",
        TrackActiveUsersCache.DEFAULT_CACHE_TTL.toMillis(), MILLISECONDS)
    inactivityCutOff = Duration.ofMillis(cacheTtl)
    gracePeriodEnd = scriptInstallDate + cacheTtl
    ignoreAccountIds = ignoreAccountIdsFromConfig("ignoreAccountId")
    autoDisableInterval = timeUnitFromConfig("autoDisableInterval", Duration.ofHours(1))
  }

  private long timeUnitFromConfig(String name, Duration defaultValue) {
    def value = config.getString(name)
    if (value) {
      ConfigUtil.getTimeUnit(value, defaultValue.toMillis(), MILLISECONDS)
    } else {
      defaultValue.toMillis()
    }
  }

  private Set<Account.Id> ignoreAccountIdsFromConfig(String name) {
    def strings = config.getStringList(name) as Set
    strings.stream()
        .map(Account.Id.&tryParse)
        .filter { it.isPresent() }
        .map { it.get() }
        .collect(Collectors.toSet())
  }

  private static long getScriptInstallDate(Path pluginData) {
    final installTimestampFile = pluginData.resolve("install_timestamp.txt")
    if (installTimestampFile.toFile().exists()) {
      def lines = Files.readAllLines(installTimestampFile)
      if (!lines.empty) {
        return Long.parseLong(lines.get(0))
      }
    }

    def now = System.currentTimeMillis()
    Files.writeString(installTimestampFile, now.toString())
    return now
  }
}

class AutoDisableInactiveUsersExecutor implements Runnable {
  static final FluentLogger logger = FluentLogger.forEnclosingClass()

  @Inject
  Accounts accounts

  @Inject
  @PluginName
  String pluginName

  @Inject
  @ServerInitiated
  Provider<AccountsUpdate> accountsUpdate

  @Inject
  ServiceUserClassifier serviceUserClassifier

  @Inject
  @Named(TrackActiveUsersCache.NAME)
  Cache<Integer, Long> trackActiveUsersCache

  @Inject
  AutoDisableInactiveUsersConfig autoDisableConfig

  @Override
  void run() {
    try {
      def now = System.currentTimeMillis()
      if (autoDisableConfig.gracePeriodEnd > now) {
        return
      }

      def allActiveAccounts = accounts.all().findAll {
        it.account().isActive() && !serviceUserClassifier.isServiceUser(it.account().id())
      }
      def accountsToDisable = allActiveAccounts.findAll { accountState ->
        !trackActiveUsersCache.getIfPresent(accountState.account().id().get()) && !isIgnored(accountState)
      }.collect { it.account() }

      if (!accountsToDisable.isEmpty() && accountsToDisable.size() != allActiveAccounts.size()) {
        accountsToDisable.each { disableAccount(it) }

        logger.atInfo().log(
            "Automatically disabled %d user(s) after %d days of inactivity",
            accountsToDisable.size(),
            autoDisableConfig.inactivityCutOff.toDays())
      }
    } catch (Exception e) {
      logger.atSevere().withCause(e).log("Automatic disablement of inactive users failed")
    }
  }

  private boolean isIgnored(AccountState state) {
    autoDisableConfig.ignoreAccountIds.contains(state.account().id())
  }

  private void disableAccount(Account account) {
    def accountId = account.id()
    accountsUpdate.get().update(
        """Automatically disabling after inactivity

Disabled by ${pluginName} Groovy plugin""",
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
  private static final def INITIAL_DELAY = Duration.ofSeconds(5).toMillis()

  @Inject
  WorkQueue workQueue

  @Inject
  AutoDisableInactiveUsersExecutor autoDisableExecutor

  @Inject
  AutoDisableInactiveUsersConfig autoDisableConfig

  ScheduledExecutorService queue

  @Override
  void start() {
    queue = workQueue.createQueue(1, "autoDisableInactiveUsers")
    queue.scheduleAtFixedRate(
        autoDisableExecutor,
        INITIAL_DELAY,
        autoDisableConfig.autoDisableInterval,
        MILLISECONDS)
  }

  @Override
  void stop() {
    if (!queue) {
      queue.shutdown()
    }
  }
}

class TrackAndDisableInactiveUsersModule extends LifecycleModule {
  @Override
  void configure() {
    install(new TrackActiveUsersCache())
    listener().to(AutoDisableInactiveUsersListener)

    DynamicSet.bind(binder(), GroupBackend).to(TrackingGroupBackend)
  }
}

modules = [TrackAndDisableInactiveUsersModule]
