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
import com.google.gerrit.common.*
import com.google.gerrit.entities.*
import com.google.gerrit.extensions.registration.*
import com.google.gerrit.server.*
import com.google.gerrit.server.account.*
import com.google.gerrit.server.cache.*
import com.google.gerrit.server.project.*
import com.google.inject.*
import com.google.inject.name.*

import java.time.*

class TrackActiveUsersCache extends CacheModule {
  static final NAME = "users_cache"
  static final DEFAULT_CACHE_TTL = Duration.ofDays(90)

  @Override
  protected void configure() {
    persist(NAME, Integer, Long)
        .diskLimit(Long.MAX_VALUE)
        .maximumWeight(Long.MAX_VALUE)
        .expireAfterWrite(DEFAULT_CACHE_TTL)
  }
}

class PerUserRateLimiter {
  private final Cache<Integer, Boolean> cache = CacheBuilder.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(60))
      .build()

  boolean shouldThrottle(CurrentUser user) {
    def userKey = user.getAccountId().get()
    def lock = Optional.ofNullable(cache.getIfPresent(userKey))

    return lock.orElseGet { ->
      cache.put(userKey, true)
      return false
    }
  }
}

@Singleton
class TrackingGroupBackend implements GroupBackend {
  private final perUserRateLimit = new PerUserRateLimiter()

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
    return List.of()
  }

  @Override
  GroupMembership membershipsOf(CurrentUser user) {
    if (user.identifiedUser && !perUserRateLimit.shouldThrottle(user)) {
      trackActiveUsersCache.put(user.accountId.get(), System.currentTimeMillis())
    }
    return GroupMembership.EMPTY
  }

  @Override
  boolean isVisibleToAll(AccountGroup.UUID uuid) {
    return false
  }
}

class TrackActiveUsersModule extends AbstractModule {
  @Override
  void configure() {
    install(new TrackActiveUsersCache())

    DynamicSet.bind(binder(), GroupBackend).to(TrackingGroupBackend)
  }
}

modules = [TrackActiveUsersModule]