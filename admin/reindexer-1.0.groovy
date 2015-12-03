import com.google.gerrit.sshd.*
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.lucene.*
import com.google.inject.*
import org.kohsuke.args4j.*

@Export("start")
@CommandMetaData(name = "start", description = "Start a new on-line re-indexing for a target Lucene index version")
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
