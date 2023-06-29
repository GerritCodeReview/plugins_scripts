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
import com.google.inject.*
import com.google.gerrit.metrics.*
import org.kohsuke.args4j.*
import com.google.gerrit.server.git.*
import com.google.gerrit.entities.*
import org.eclipse.jgit.errors.*
import com.gerritforge.gerrit.globalrefdb.*
import org.eclipse.jgit.lib.*
import java.util.function.BiConsumer

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

  void registerNumRefsMetric(Map<String, Integer> projectsAndNumRefs) {
    CallbackMetric1<String, Integer> numRefsMetric =
        metrics.newCallbackMetric(
            "localrefdb/num_refs_per_project",
            Integer.class,
            new Description("Number of local refs").setGauge(),
            Field.ofString("repository_name", { it.projectName } as BiConsumer)
                .description("The name of the repository.")
                .build())
    metrics.newTrigger(
        numRefsMetric, { ->
      projectsAndNumRefs.each { e ->
        numRefsMetric.set(e.key, e.value)
      }
      numRefsMetric.prune()
    })
  }
}

@Export("count-refs")
@CommandMetaData(description = "Count the local number of refs, excluding user edits, and publish the value as 'num_refs' metric")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class CountRefs extends BaseSshCommand {

  @Argument(index = 0, usage = "Project name", metaVar = "PROJECTS", required = true)
  String[] projects

  @Inject
  GitRepositoryManager repoMgr

  @Inject
  RefDbMetrics refdbMetrics

  public void run() {
    def projectsAndNumRefs = [:]

    projects.each { project ->
      try {
        def projectName = Project.nameKey(project)

        repoMgr.openRepository(projectName).with { repo ->
          println "Counting refs of project $project ..."
          def startTime = System.currentTimeMillis()
          def filteredRefs = repo.refDatabase.refs.findAll { ref -> !(ref.name.startsWith("refs/users/.*")) && !ref.symbolic }
          println "Result: $project has ${filteredRefs.size()} refs"
          projectsAndNumRefs[project] = filteredRefs.size()
        }
      } catch (RepositoryNotFoundException e) {
        error "Project $project not found"
      }
    }
    refdbMetrics.registerNumRefsMetric(projectsAndNumRefs)
  }
}

commands = [CountRefs]

