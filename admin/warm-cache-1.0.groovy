// Copyright (C) 2016 The Android Open Source Project
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

import com.google.gerrit.common.data.GlobalCapability
import com.google.gerrit.reviewdb.client.Account
import com.google.gerrit.server.git.WorkQueue
import com.google.gerrit.sshd.*
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.server.project.*
import com.google.gerrit.server.account.*
import com.google.gerrit.server.IdentifiedUser
import com.google.gerrit.reviewdb.client.AccountGroup
import com.google.gerrit.reviewdb.server.ReviewDb
import com.google.inject.*
import org.apache.sshd.server.Environment

import java.util.concurrent.ExecutorService

abstract class BaseSshCommand extends SshCommand {

  void println(String msg) {
    stdout.println msg
    stdout.flush()
  }
}

@Export("projects")
@CommandMetaData(description = "Warm-up project_list and projects caches")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class WarmProjectsCache extends BaseSshCommand {

  @Inject
  ProjectCache cache

  @Inject
  GroupCache groupCache

  public void run() {
    println "Loading project list ..."
    def start = System.currentTimeMillis()
    def allProjects = cache.all()
    def totProjects = allProjects.size()
    def loaded = 0

    for ( project in allProjects ) {
      cache.get(project)
      loaded++
      if (loaded%1000==0) {
        println "$loaded of $totProjects projects"
      }
    }

    def elapsed = (System.currentTimeMillis()-start)/1000
    println "$loaded projects loaded in $elapsed secs"
  }
}

@Export("groups")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class WarmGroupsCache extends WarmProjectsCache {

  @Inject
  GroupCache groupCache

  @Inject
  GroupIncludeCache groupIncludeCache

  private HashSet<AccountGroup.UUID> allGroupsUUIDs() {
    def allGroupsUuids = new HashSet<AccountGroup.UUID>()
    for (project in cache.all()) {
      def groupUuids = cache.get(project)?.getConfig()?.getAllGroupUUIDs()
      if (groupUuids != null) { allGroupsUuids.addAll(groupUuids) }
    }
    allGroupsUuids.addAll(groupIncludeCache.allExternalMembers())
    return allGroupsUuids;
  }

  public void run() {
    println "Loading groups list ..."
    def start = System.currentTimeMillis()
    def allGroupsUuids = allGroupsUUIDs();
    def totGroups = allGroupsUuids.size()
    def groupsLoaded = 0

    for (groupUuid in allGroupsUuids) {
      if(groupUuid.get().length() == 0) {
        continue
      }
      groupIncludeCache.parentGroupsOf(groupUuid)
      def group = groupCache.get(groupUuid)

      if(group.isPresent()) {
        groupCache.get(group.get().getNameKey())
        groupCache.get(group.get().getId())
      }
      groupsLoaded++

      if (groupsLoaded%1000==0) {
        println "$groupsLoaded of $totGroups groups"
      }
    }

    def elapsed = (System.currentTimeMillis()-start)/1000
    println "$groupsLoaded groups loaded in $elapsed secs"
  }
}

@Export("accounts")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class WarmAccountsCache extends BaseSshCommand {

  @Inject
  AccountCache cache

  @Inject
  Accounts accounts

  @Inject
  Provider<ReviewDb> db

  public void run() {
    println "Loading accounts ..."
    def start = System.currentTimeMillis()
    def allAccountIds = accounts.allIds()
    def loaded = 0

    for (accountId in allAccountIds) {
      cache.get(accountId)
      loaded++
      if (loaded%1000==0) {
        println "$loaded accounts"
      }
    }

    def elapsed = (System.currentTimeMillis()-start)/1000
    println "$loaded accounts loaded in $elapsed secs"
  }
}

@Export("groups-backends")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class WarmGroupsBackendsCache extends WarmAccountsCache {

  private static class GroupsBackendsTask implements Runnable {
    Account.Id accountId

    GroupsBackendsTask(Account.Id id) {
      accountId = id
    }

    @Override
    void run() {
      def user = userFactory.create(accountId)
      user?.getEffectiveGroups()?.getKnownGroups()
    }
    @Override
    String toString() {
      return "Warmup backend groups [accountId: $accountId]"
    }
  }

  @Inject
  IdentifiedUser.GenericFactory userFactory

  @Inject WorkQueue queues

  ExecutorService executorService

  ExecutorService createExecutor() {
    queues.createQueue(8, "Groups-Backend-Cache-Warmer", false);
  }

  @Override
  void start(Environment env) throws IOException {
    super.start(env)
    if(executorService == null) {
      executorService = createExecutor()
    }
  }

  void run() {
    println "Scheduling LDAP groups loading ..."
    def start = System.currentTimeMillis()
    def allAccountIds = accounts.allIds()

    def scheduled = 0
    def lastDisplay = 0

    for (accountId in allAccountIds) {
      scheduled++
      executorService.submit(new GroupsBackendsTask(accountId))

      if (scheduled.intdiv(1000) > lastDisplay) {
        println "Scheduled loading of groups for $scheduled accounts"
        lastDisplay = scheduled.intdiv(1000)
      }
    }

    def elapsed = (System.currentTimeMillis() - start) / 1000
    println "Scheduled loading of groups for $scheduled accounts in $elapsed secs"
  }
}

commands = [ WarmProjectsCache, WarmGroupsCache, WarmAccountsCache, WarmGroupsBackendsCache ]

