import com.google.gerrit.extensions.annotations.Listen
import com.google.gerrit.server.events.CommitReceivedEvent
import com.google.gerrit.server.git.validators.CommitValidationException
import com.google.gerrit.server.git.validators.CommitValidationListener
import com.google.gerrit.server.git.validators.CommitValidationMessage

import com.google.inject.Singleton
import com.google.inject.Inject

@Singleton
@Listen
public class DoNotCommitHook implements CommitValidationListener {

        def READ_ONLY_MSG = "Gerrit is under maintenance, all projects are READ ONLY"

	@Override
  	public List<CommitValidationMessage> onCommitReceived(CommitReceivedEvent receiveEvent) throws CommitValidationException {
		throw new CommitValidationException(READ_ONLY_MSG)
	}
}

