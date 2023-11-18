import com.google.common.flogger.FluentLogger
import com.google.gerrit.entities.Project
import com.google.gerrit.extensions.annotations.Listen
import com.google.gerrit.extensions.annotations.PluginName
import com.google.gerrit.extensions.events.LifecycleListener
import com.google.gerrit.metrics.CallbackMetric1
import com.google.gerrit.metrics.Description
import com.google.gerrit.metrics.Field
import com.google.gerrit.metrics.MetricMaker
import com.google.gerrit.server.config.PluginConfig
import com.google.gerrit.server.config.PluginConfigFactory
import com.google.gerrit.server.git.GitRepositoryManager
import com.google.gerrit.server.git.WorkQueue
import com.google.gerrit.server.project.ProjectCache
import com.google.inject.Singleton
import org.eclipse.jgit.lib.Repository
import org.h2.store.fs.FileUtils

import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import SanitizeProjectName

@Singleton
@Listen
class ProjectsPackedRefsStalenessCheckers implements LifecycleListener {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass()

  @Inject
  @PluginName
  String pluginName
  @Inject
  PluginConfigFactory configFactory
  @Inject
  GitRepositoryManager repoMgr
  @Inject
  ProjectCache projectCache
  @Inject
  WorkQueue workQueue
  @com.google.inject.Inject
  RefDbMetrics refdbMetrics

  private ScheduledFuture<?> scheduledCheckerTask
  private String[] projectPrefixes

  def STALE_MAX_AGE_SEC_DEFAULT = 300
  def CHECK_INTERVAL_SEC_DEFAULT = 10
  int staleMaxAgeSec

  SanitizeProjectName sanitizeProjectName = new SanitizeProjectName()

  @Override
  void start() {
    if (scheduledCheckerTask != null) {
      return;
    }

    PluginConfig pluginConfig = configFactory.getFromGerritConfig(pluginName)
    def checkInterval = pluginConfig.getInt("checkIntervalSec", CHECK_INTERVAL_SEC_DEFAULT)
    staleMaxAgeSec = pluginConfig.getInt("staleMaxAgeSec", STALE_MAX_AGE_SEC_DEFAULT)
    projectPrefixes = pluginConfig.getStringList("projectPrefix")

    scheduledCheckerTask = workQueue.getDefaultQueue().scheduleAtFixedRate({ checkProjects() }, checkInterval, checkInterval, TimeUnit.SECONDS)
    logger.atInfo().log("packed-refs.lock staleness checker started for %d projects (checkIntervalSec=%d, staleMaxAgeSec=%d, projectPrefix=%s)",
        allProjectsToCheck().size(), checkInterval, staleMaxAgeSec, Arrays.copyOf(projectPrefixes, projectPrefixes.length))
  }

  private def allProjectsToCheck() {
    projectCache.all()
        .collect { it.get() }
        .findAll { String projectName ->
          projectPrefixes.find { projectName.startsWith(it) } != null
        }
  }

  @Override
  void stop() {
    logger.atInfo().log("Stopping all projects staleness checker ...")
    scheduledCheckerTask?.cancel(true)
  }

  def checkProjects() {
    def threadNameOrig = Thread.currentThread().getName()
    try {
      Thread.currentThread().setName("packed-refs.lock checker")
      allProjectsToCheck().each { String projectName ->
        repoMgr.openRepository(Project.nameKey(projectName)).with { Repository it ->
          try {
            recordLockFileAgeMetric(it, projectName)
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error whilst checking project %s", projectName)
          }
        }
      }
    } finally {
      Thread.currentThread().setName(threadNameOrig)
    }
  }

  private void recordLockFileAgeMetric(Repository repo, String projectName) {
    def repoDir = repo.getDirectory()
    logger.atFine().log("Checking project %s ... ", projectName)
    File packedRefsLock = new File(repoDir, "packed-refs.lock")
    if (!packedRefsLock.exists()) {
      refdbMetrics.projectsAndLockFileAge.put(SanitizeProjectName.sanitize("a_project_name"), 0)
      logger.atFine().log("[%s] locked file didn't exists", projectName)
      return
    }

    def packedRefsLockMillis = FileUtils.lastModified(packedRefsLock.getAbsolutePath())
    def lockFileAge = System.currentTimeMillis() - packedRefsLockMillis
      refdbMetrics.projectsAndLockFileAge.put(SanitizeProjectName.sanitize("a_project_name"), lockFileAge)
      logger.atFine().log("[%s] calculated age for lock file (creationMillis=%d)", projectName, lockFileAge)
    }
  }
}

@Singleton
class RefDbMetrics implements LifecycleListener {
  FluentLogger log = FluentLogger.forEnclosingClass()

  @com.google.inject.Inject
  MetricMaker metrics

  CallbackMetric1<String, Long> lockFileAgeMetric
  final Map<String, Long> projectsAndLockFileAge = new ConcurrentHashMap()

  void setupTrigger(CallbackMetric1<String, Long> lockFileAgeMetric, Map<String, Long> projectsAndLockFileAge) {
    metrics.newTrigger(
        lockFileAgeMetric, { ->
      if (projectsAndLockFileAge.isEmpty()) {
        lockFileAgeMetric.forceCreate("")
      } else {
        projectsAndLockFileAge.each { e ->
          lockFileAgeMetric.set(e.key, e.value)
        }
        projectsAndLockFileAge.prune()
      }
    })
  }

  CallbackMetric1<String, Long> createCallbackMetric(String name, String description) {
    metrics.newCallbackMetric(
        name,
        Long.class,
        new Description(description).setGauge(),
        Field.ofString("repository_name", { it.projectName } as BiConsumer)
            .description(description)
            .build())
  }
  void start() {
    lockFileAgeMetric = createCallbackMetric("stalefilechecker/stale_file_age_per_project", "Age of lock file")

    setupTrigger(lockFileAgeMetric, projectsAndLockFileAge)
  }

  void stop() {
    projectsAndLockFileAge.remove()
  }
}
