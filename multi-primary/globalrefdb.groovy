// Copyright (C) 2023 The Android Open Source Project
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

import com.google.gerrit.common.data.*
import com.google.gerrit.sshd.*
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.server.project.*
import com.google.gerrit.server.account.*
import com.google.gerrit.server.IdentifiedUser
import com.google.inject.*
import org.kohsuke.args4j.*
import com.google.gerrit.server.git.*
import com.google.gerrit.entities.*
import org.eclipse.jgit.errors.*
import com.gerritforge.gerrit.globalrefdb.*
import com.google.gerrit.extensions.registration.*
import org.eclipse.jgit.lib.*

abstract class BaseSshCommand extends SshCommand {
  void println(String msg) {
    stdout.println msg
    stdout.flush()
  }

  void error(String msg) {
    stderr.println msg
    stderr.flush()
  }
}

@Export("check")
@CommandMetaData(description = "Check local refs alignment against the global-refdb for a project")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class ProjectRefsCheck extends BaseSshCommand {

  @Argument(index = 0, usage = "Project name", metaVar = "PROJECT", required = true)
  String project

  @Option(name = "--verbose", usage = "Display verbose check results for all refs")
  boolean verbose = false

  @Option(name = "--ref", usage = "Check only one ref")
  String singleRef

  @Inject
  GitRepositoryManager repoMgr

  @Inject
  DynamicItem<GlobalRefDatabase> globalRefDb

  public void run() {
    try {
      def projectName = Project.nameKey(project)

      repoMgr.openRepository(projectName).with { repo ->
        def upToDate = true

        if (singleRef) {
          println "Checking project $project:$singleRef ..."
          def ref = repo.refDatabase.exactRef(singleRef)
          if (!ref) {
            error "Project $project does not have $singleRef"
            return
          }

          upToDate = checkRef(projectName, repo, ref)
        } else {
          println "Checking project $project ..."
          def totRefs = repo.refDatabase.refs.size()
          def refsDone = 0
          def refsDonePerc = 0
          def startTime = System.currentTimeMillis()
          repo.refDatabase.refs.each { ref ->
            checkRef(projectName, repo, ref)
            if (!verbose) {
              refsDone++
              if ((refsDone * 100).intdiv(totRefs) > refsDonePerc) {
                refsDonePerc = (refsDone * 100).intdiv(totRefs)
                def totTime = startTime + Math.round((System.currentTimeMillis() - startTime) / refsDonePerc * 100)
                def eta = Math.round((totTime - System.currentTimeMillis()) / 1000)
                println "  $refsDone/$totRefs ($refsDonePerc%, ETA $eta sec)"
              }
            }
          }
        }

        println "Result: ${upToDate ? 'UP-TO-DATE':'OUTDATED'}"
      }
    } catch (RepositoryNotFoundException e) {
      error "Project $project not found"
    }
  }

  boolean checkRef(Project.NameKey projectName, Repository repo, Ref ref)
  {
    def isUpToDate = globalRefDb.get().isUpToDate(projectName, ref)

    if (verbose && isUpToDate) {
      println "[UP-TO-DATE] ${ref.name}"
    }

    if (!isUpToDate) {
      def globalRef = globalRefDb.get().get(projectName, ref.name, String.class)
      println "[OUTDATED] ${ref.name}:${ref.objectId.name} <> ${globalRef.orElse('MISSING')}"
    }
  }
}

commands = [ ProjectRefsCheck ]

