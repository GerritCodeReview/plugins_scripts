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
import com.google.gerrit.metrics.*
import org.kohsuke.args4j.*
import com.google.gerrit.server.git.*
import com.google.gerrit.entities.*
import org.eclipse.jgit.errors.*
import com.gerritforge.gerrit.globalrefdb.*
import com.google.gerrit.extensions.registration.*
import org.eclipse.jgit.lib.*
import java.security.MessageDigest

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

@Singleton
class RefDbMetrics {
  @Inject
  MetricMaker metrics

  RegistrationHandle numRefsMetric
  RegistrationHandle sumRefsMetric

  void registerNumRefsMetric(String project, int numRefs) {
    if (numRefsMetric != null) {
      numRefsMetric.remove()
      numRefsMetric = null
    }

    numRefsMetric = metrics.newCallbackMetric("localrefdb/num_refs/" + project,
                                              Integer.class,
                                              new Description("Number of local refs").setGauge(),
                                              { -> numRefs })
  }

    void registerSumRefsMetric(String project, int sumRefs) {
    if (sumRefsMetric != null) {
      sumRefsMetric.remove()
      sumRefsMetric = null
    }

    sumRefsMetric = metrics.newCallbackMetric("localrefdb/sum_refs/" + project,
                                              Integer.class,
                                              new Description("Sum of all SHA-1 of local refs").setGauge(),
                                              { -> sumRefs })
  }
}

@Export("count-refs")
@CommandMetaData(description = "Count the local number of refs, excluding user edits, and publish the value as 'num_refs' metric")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class CountRefs extends BaseSshCommand {

  @Argument(index = 0, usage = "Project name", metaVar = "PROJECT", required = true)
  String project

  @Inject
  GitRepositoryManager repoMgr

  @Inject
  RefDbMetrics refdbMetrics

  public void run() {
    try {
      def projectName = Project.nameKey(project)

      repoMgr.openRepository(projectName).with { repo ->
        def upToDate = true

        println "Counting refs of project $project ..."
        def totRefs = repo.refDatabase.refs.size()
        def startTime = System.currentTimeMillis()
        def filteredRefs = repo.refDatabase.refs.findAll{ ref -> !(ref.name.startsWith("refs/users/.*")) && !ref.symbolic}
        println "Result: $project has ${filteredRefs.size()} refs"

        refdbMetrics.registerNumRefsMetric(project, filteredRefs.size())
      }
    } catch (RepositoryNotFoundException e) {
      error "Project $project not found"
    }
  }


}

@Export("sum-refs")
@CommandMetaData(description = "Sum the local number of refs, excluding user edits, and publish the value as 'sum_refs' metric")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class SumRefs extends BaseSshCommand {

  @Argument(index = 0, usage = "Project name", metaVar = "PROJECT", required = true)
  String project

  @Inject
  GitRepositoryManager repoMgr

  @Inject
  RefDbMetrics refdbMetrics

  public void run() {
    try {
      def projectName = Project.nameKey(project)

      repoMgr.openRepository(projectName).with { repo ->
        def upToDate = true

        println "Adding refs of project $project ..."
        def totRefs = repo.refDatabase.refs.size()
        def refsDone = 0
        def refsDonePerc = 0
        def startTime = System.currentTimeMillis()
        def filteredRefs = repo.refDatabase.refs.findAll{ ref -> !(ref.name.startsWith("refs/users/.*")) && !ref.symbolic}
        println "Result: $project has ${filteredRefs.size()} refs"
        def md = MessageDigest.getInstance("SHA-1")
        filteredRefs.sort().each { ref -> md.update(ref.getObjectId)}

        def sha1Sum = md.digest().encodeBase64().toString()
        println("MD Digest of sum of all SHA1 for project $project is: $outStr")
        refdbMetrics.registerSumRefsMetric(project, sha1Sum)
      }
    } catch (RepositoryNotFoundException e) {
      error "Project $project not found"
    }
  }


}
commands = [ CountRefs, SumRefs ]