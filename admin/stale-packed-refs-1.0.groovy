import com.google.common.flogger.FluentLogger
import com.google.gerrit.entities.Project
import com.google.gerrit.extensions.annotations.Listen
import com.google.gerrit.extensions.annotations.PluginName
import com.google.gerrit.extensions.events.LifecycleListener
import com.google.gerrit.server.config.PluginConfig
import com.google.gerrit.server.config.PluginConfigFactory
import com.google.gerrit.server.git.GitRepositoryManager
import com.google.gerrit.server.git.WorkQueue
import com.google.gerrit.server.project.ProjectCache
import com.google.inject.Singleton
import org.eclipse.jgit.lib.Repository
import org.h2.store.fs.FileUtils

import javax.inject.Inject
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

  private ScheduledFuture<?> scheduledCheckerTask
  private String[] projectPrefixes

  def STALE_MAX_AGE_SEC_DEFAULT = 300
  def CHECK_INTERVAL_SEC_DEFAULT = 10
  int staleMaxAgeSec

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
            checkProjectPackedRefs(it, projectName)
          } catch (Exception e) {
            logger.atSevere().withCause(e).log("Error whilst checking project %s", projectName)
          }
        }
      }
    } finally {
      Thread.currentThread().setName(threadNameOrig)
    }
  }

  private void checkProjectPackedRefs(Repository repo, String projectName) {
    def repoDir = repo.getDirectory()
    logger.atFine().log("Checking project %s ... ", projectName)
    File packedRefsLock = new File(repoDir, "packed-refs.lock")
    if (!packedRefsLock.exists()) {
      return
    }

    def packedRefsLockMillis = FileUtils.lastModified(packedRefsLock.getAbsolutePath())
    if (System.currentTimeMillis() > (packedRefsLockMillis + (staleMaxAgeSec * 1000))) {
      boolean deleteSucceeded = packedRefsLock.delete()
      logger.atWarning().log("[%s] packed-refs.lock is stale (creationMillis=%d): %s", projectName, packedRefsLockMillis, deleteSucceeded ? "DELETED" : "DELETE FAILED")
    }
  }
}
