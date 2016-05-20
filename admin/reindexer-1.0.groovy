// Copyright (C) 2015 The Android Open Source Project
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
import com.google.gerrit.sshd.*
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.lucene.*
import com.google.inject.*
import org.kohsuke.args4j.*

@Export("start")
@CommandMetaData(name = "start", description = "Start a new on-line re-indexing for a target Lucene index version")
@RequiresCapability(GlobalCapability.ADMINISTRATE_SERVER)
class StartReindex extends SshCommand {

  @Inject OnlineReindexer.Factory reindexerFactory

  @Argument(index = 0, usage = "Index version", metaVar = "VERSION")
  int indexVersion

  public void run() {
    def indexer = reindexerFactory.create(indexVersion)
    indexer.start()
    stdout.println "On-line reindexing scheduled for version " + indexVersion
  }
}
