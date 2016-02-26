// Copyright (C) 2016 The Android Open Source Project
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
  public List<CommitValidationMessage> onCommitReceived(
      CommitReceivedEvent receiveEvent) throws CommitValidationException {
    throw new CommitValidationException(READ_ONLY_MSG)
  }
}

