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
import com.google.gerrit.entities.AccountGroup
import com.google.gerrit.sshd.*
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.server.project.*
import com.google.gerrit.server.account.*
import com.google.gerrit.server.IdentifiedUser
import com.google.inject.*
import org.kohsuke.args4j.*

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
      def groupUuids = cache.get(project).map {projectState -> projectState.getConfig().getAllGroupUUIDs() }
      if (groupUuids.isPresent()) { allGroupsUuids.addAll(groupUuids.get()) }
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
      groupIncludeCache.parentGroupsOf(groupUuid)
      def group = groupCache.get(groupUuid)
      if(group.isPresent()) {
        groupCache.get(group.get()?.getNameKey())
        groupCache.get(group.get()?.getId())
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

  public void run() {
    println "Loading accounts ..."
    def start = System.currentTimeMillis()
    def allAccounts = accounts.all()
    def loaded = 0

    for (account in allAccounts) {
      cache.get(account.account().id())
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

  @Inject
  IdentifiedUser.GenericFactory userFactory

  public void run() {
    println "Loading groups ..."
    def start = System.currentTimeMillis()
    def allAccounts = accounts.all()
    def loaded = 0
    def allGroupsUUIDs = new HashSet<AccountGroup.UUID>()
    def lastDisplay = 0

    for (account in allAccounts) {
      def user = userFactory.create(account.account().id())
      def groupsUUIDs = user?.getEffectiveGroups()?.getKnownGroups()
      if (groupsUUIDs != null) { allGroupsUUIDs.addAll(groupsUUIDs) }

      loaded = allGroupsUUIDs.size()
      if (loaded.intdiv(1000) > lastDisplay) {
        println "$loaded groups"
        lastDisplay = loaded.intdiv(1000)
      }
    }

    def elapsed = (System.currentTimeMillis()-start)/1000
    println "$loaded groups loaded in $elapsed secs"
  }
}

commands = [ WarmProjectsCache, WarmGroupsCache, WarmAccountsCache, WarmGroupsBackendsCache ]

