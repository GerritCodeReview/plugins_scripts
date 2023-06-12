// Copyright (C) 2020 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License")
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
import com.google.gerrit.extensions.annotations.Export
import com.google.gerrit.extensions.annotations.RequiresCapability
import com.google.gerrit.sshd.CommandMetaData
import com.google.gerrit.sshd.SshCommand
import org.kohsuke.args4j.Argument

import com.google.gerrit.entities.Project
import com.google.gerrit.server.git.GitRepositoryManager
import com.google.inject.Inject
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.errors.CorruptObjectException
import org.eclipse.jgit.errors.IncorrectObjectTypeException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter


@Export("size")
@CommandMetaData(description = "Get size of specific revision")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class ProjectRevisionReader extends SshCommand {

  @Argument(index = 0, usage = "Project name", metaVar = "PROJECT", required = true)
  String project

  @Argument(index = 1, usage = "Ref name", metaVar = "ref", required = true)
  String ref

  private GitRepositoryManager gitRepositoryManager

  @Inject
  RevisionReader(GitRepositoryManager gitRepositoryManager) {
    this.gitRepositoryManager = gitRepositoryManager
  }

  Long read(Project.NameKey project, String refName) {
    try {
      Repository git = gitRepositoryManager.openRepository(project)
      Long totalRefSize = 0l

      Ref ref = git.exactRef(refName)
      if (ref == null) {
        throw new IllegalArgumentException("Ref doesn't exist")
      }

      ObjectId objectId = ref.getObjectId()

      ObjectLoader commitLoader = git.open(objectId)
      totalRefSize += commitLoader.getSize()

      if (commitLoader.getType() != Constants.OBJ_COMMIT) {
        throw new IllegalArgumentException(String.format(
            "Ref {} for project {} points to an object type {}",
            refName,
            project,
            commitLoader.getType()))
      }

      RevCommit commit = RevCommit.parse(commitLoader.getCachedBytes())

      RevTree tree = commit.getTree()
      ObjectId treeObjectId = commit.getTree().toObjectId()
      ObjectLoader treeLoader = git.open(treeObjectId)
      totalRefSize += treeLoader.getSize()

      try {
        TreeWalk walk = new TreeWalk(git)
        if (commit.getParentCount() > 0) {
          List<DiffEntry> diffEntries = readDiffs(git, commit, tree, walk)
          totalRefSize += readBlobs(git, diffEntries)
        } else {
          walk.setRecursive(true)
          walk.addTree(tree)
          totalRefSize += readBlobs(git, walk)
        }
        walk.close()
      } finally {}

      git.close()
      return totalRefSize
    } finally {}
  }

  private List<DiffEntry> readDiffs(Repository git, RevCommit commit, RevTree tree, TreeWalk walk)
      throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
          IOException {
    walk.setFilter(TreeFilter.ANY_DIFF)
    walk.reset(getParentTree(git, commit), tree)
    return DiffEntry.scan(walk, true)
  }

  private static Long readBlobs(
      Repository git, TreeWalk walk)
      throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
          IOException {
    Long blobSize = 0
    while (walk.next()) {
      ObjectId objectId = walk.getObjectId(0)
      ObjectLoader objectLoader = git.open(objectId)
      blobSize += objectLoader.getSize()
    }
    return blobSize
  }

  /**
   * Reads and evaluates the git objects in this revision. The following are filtered out:
   * <li>DELETE changes
   * <li>git submodule commits, because the git commit hash is not present in this repo.
   *
   *     <p>The method keeps track of the total size of all objects it has processed, and verifies
   *     it is below the acceptable threshold.
   *
   * @param git - this git repo, used to load the objects
   * @param diffEntries - a list of the diff entries for this revision
   * @throws MissingObjectException - if the object can't be found
   * @throws IOException - if processing failed for another reason
   */
  private Long readBlobs(
      Repository git,
      List<DiffEntry> diffEntries)
      throws MissingObjectException, IOException {
    Long blobSize = 0
    for (DiffEntry diffEntry : diffEntries) {
      if (!(ChangeType.DELETE.equals(diffEntry.getChangeType()) || gitSubmoduleCommit(diffEntry))) {
        ObjectId diffObjectId = diffEntry.getNewId().toObjectId()
        ObjectLoader objectLoader = git.open(diffObjectId)
        blobSize += objectLoader.getSize()

      }
    }

    return blobSize
  }

  private static boolean gitSubmoduleCommit(DiffEntry diffEntry) {
    return diffEntry.getNewMode().equals(FileMode.GITLINK)
  }

  private static RevTree getParentTree(Repository git, RevCommit commit)
      throws MissingObjectException, IOException {
    RevCommit parent = commit.getParent(0)
    ObjectLoader parentLoader = git.open(parent.getId())
    RevCommit parentCommit = RevCommit.parse(parentLoader.getCachedBytes())
    return parentCommit.getTree()
  }

  public void run() throws Exception {
    stdout.println read(Project.nameKey(project), ref)
    stdout.flush()
  }
}

commands = [ ProjectRevisionReader ]

