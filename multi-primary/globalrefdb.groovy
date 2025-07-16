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
    stderr.println "[ERROR] $msg"
    stderr.flush()
  }

  void warning(String msg) {
    stderr.println "[WARNING] $msg"
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
          def refsToCheck = repo.refDatabase.refs.findAll {elem -> elem.name =~ "refs/changes.*/meta" || !elem.name.startsWith("refs/changes")}
          def totRefs = refsToCheck.size()
          def refsDone = 0
          def refsDonePerc = 0
          def startTime = System.currentTimeMillis()
          refsToCheck.parallelStream().forEach { ref ->
            def refUpToDate = checkRef(projectName, repo, ref)
            upToDate = upToDate && refUpToDate
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

        println "Result: $project is ${upToDate ? 'UP-TO-DATE':'OUTDATED'}"
      }
    } catch (RepositoryNotFoundException e) {
      error "Project $project not found"
    }
  }

  boolean checkRef(Project.NameKey projectName, Repository repo, Ref ref)
  {
    // refs/multi-site/version is just a tracking of the update ts of the repo
    if (ref.getName() == "refs/multi-site/version") {
      return true;
    }

    def isUpToDate = globalRefDb.get().isUpToDate(projectName, ref)

    if (verbose && isUpToDate) {
      println "[UP-TO-DATE] ${ref.name}"
    }

    if (!isUpToDate) {
      def globalRef = globalRefDb.get().get(projectName, ref.name, String.class)
      println "[OUTDATED] ${ref.name}:${ref.objectId.name} <> ${globalRef.orElse('MISSING')}"
    }

    return isUpToDate
  }
}

@Export("update-ref")
@CommandMetaData(description = "Update the global-refdb ref name/value for a project")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class ProjectRefsUpdate extends BaseSshCommand {

  @Argument(index = 0, usage = "Project name", metaVar = "PROJECT", required = true)
  String project

  @Argument(index = 1, usage = "Ref name", metaVar = "REF", required = true)
  String ref

  @Argument(index = 2, usage = "New value", metaVar = "NEWVALUE", required = true)
  String newValue

  @Inject
  GitRepositoryManager repoMgr

  @Inject
  DynamicItem<GlobalRefDatabase> globalRefDb

  public void run() {
    try {
      def projectName = Project.nameKey(project)

      repoMgr.openRepository(projectName).with { repo ->
        if (!repo.refDatabase.exactRef(ref)) {
          warning "Local project $project does not have $ref"
        }
        def currValue = globalRefDb.get().get(projectName, ref, String.class)
        if (currValue.isEmpty()) {
          error "Global-refdb for project $project does not have $ref"
        } else {
          println "Updating global-refdb ref for /$project/$ref ${currValue.get()} => $newValue ... "
          def updateDone = globalRefDb.get().compareAndPut(projectName, ref, currValue.get(), newValue)
          println "Result: /$project/$ref global-refdb update has ${updateDone ? 'SUCCEEDED':'FAILED'}"
        }
      }
    } catch (RepositoryNotFoundException e) {
      error "Project $project not found"
    }
  }
}

@Export("show-ref")
@CommandMetaData(description = "Get global-refdb refs values for a project")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class ProjectRefsGet extends BaseSshCommand {

  @Argument(index = 0, usage = "Project name", metaVar = "PROJECT", required = true)
  String project

  @Option(name = "--ref", usage = "Global-refdb ref(s) requested", required = true)
  ArrayList<String> refs

  @Inject
  DynamicItem<GlobalRefDatabase> globalRefDb

  public void run() {
    def projectName = Project.nameKey(project)

    refs.each { ref ->
      def sha = globalRefDb.get().get(projectName, ref, String.class)
      if (sha.isPresent()) {
        println "${sha.get()} ${ref}"
      }
    }
  }
}

commands = [ ProjectRefsCheck, ProjectRefsUpdate, ProjectRefsGet ]

