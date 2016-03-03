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

import com.google.gerrit.sshd.*
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.server.project.*
import com.google.gerrit.server.account.*
import com.google.gerrit.reviewdb.client.AccountGroup
import com.google.inject.*
import org.kohsuke.args4j.*

abstract class BaseSshCommand extends SshCommand {

  void println(String msg) {
    stdout.println msg
    stdout.flush()
  }
}

@Export("projects")
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

commands = [ WarmProjectsCache ]

