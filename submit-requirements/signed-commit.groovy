// Copyright (C) 2024 The Android Open Source Project
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

import com.google.gerrit.index.query.*
import com.google.gerrit.extensions.annotations.*
import com.google.gerrit.server.git.GitRepositoryManager
import com.google.gerrit.server.rules.*
import com.google.gerrit.entities.*
import com.google.gerrit.server.query.change.*
import org.eclipse.jgit.api.VerifySignatureCommand
import org.eclipse.jgit.gpg.bc.internal.*

import java.util.*
import com.google.inject.*
import org.eclipse.jgit.lib.*

import static com.google.gerrit.server.query.change.ChangeQueryBuilder.*

abstract class SignaturePredicate extends SubmitRequirementPredicate {

    private final GitRepositoryManager repoMgr

    SignaturePredicate(String operand, GitRepositoryManager repoMgr) {
        super("is", operand)
        this.repoMgr = repoMgr
    }

    @Override
    boolean match(ChangeData changeData) {

        def patchSet = changeData.currentPatchSet()

        Repository repo = repoMgr.openRepository(changeData.project())
        try {
            def commitSha1 = patchSet.commitId().getName()
            def res = new VerifySignatureCommand(repo)
                    .setVerifier(signatureVerifier())
                    .addName(commitSha1)
                    .call()
            return res.get(commitSha1)?.verification?.verified
        } finally {
            repo.close()
        }
        true
    }

    @Override
    int getCost() { 1 }

    abstract GpgSignatureVerifier signatureVerifier()
}

class X509SignaturePredicate extends SignaturePredicate {

    @Inject
    X509SignaturePredicate(@PluginName String pluginName, GitRepositoryManager repoMgr) {
        super("${HasX509Signature.OPERAND}-${pluginName}")
    }

    @Override
    GpgSignatureVerifier signatureVerifier() { new BouncyCastleCMSSignatureVerifier() }
}

class HasX509Signature implements ChangeHasOperandFactory {
    static final String OPERAND = "x509";
    @Inject
    X509SignaturePredicate x509Predicate

    @Override
    Predicate<ChangeData> create(ChangeQueryBuilder builder) { x509Predicate }
}

class GpgSignaturePredicate extends SignaturePredicate {

    @Inject
    GpgSignaturePredicate(@PluginName String pluginName, GitRepositoryManager repoMgr) {
        super("${HasGpgSignature.OPERAND}-${pluginName}")
    }

    @Override
    GpgSignatureVerifier signatureVerifier() { new BouncyCastleGpgSignatureVerifier() }
}

class HasGpgSignature implements ChangeHasOperandFactory {
    static final String OPERAND = "gpg";
    @Inject
    GpgSignaturePredicate gpgPredicate

    @Override
    Predicate<ChangeData> create(ChangeQueryBuilder builder) { gpgPredicate }
}

class X509SubmitRuleModule extends AbstractModule {

    @Override
    void configure() {
        bind(ChangeHasOperandFactory)
                .annotatedWith(Exports.named(HasX509Signature.OPERAND))
                .to(HasX509Signature);
        bind(ChangeHasOperandFactory)
                .annotatedWith(Exports.named(HasGpgSignature.OPERAND))
                .to(HasGpgSignature);
    }
}

modules = [X509SubmitRuleModule]

