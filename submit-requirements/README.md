Custom Submit Requirements
=============================

Overview
--------
Scripts for adding custom [submit requirements](https://gerrit-documentation.storage.googleapis.com/Documentation/3.9.4/config-submit-requirements.html) for Gerrit

Index
-----
* [signed-commit.groovy](signed-commit.groovy) Adds the `is:x509_signed-commit` and `is:gpg_signed-commit` requirements.

  Custom submit requirements for checking if the latest patch-set is digitally signed or not with either X.509
  (S/MIME) or GnuPG keys.

  Install the Groovy script onto the `$GERRIT_SITE/plugins` and add the following settings in the `project.config` of the
  project's `refs/meta/config`:

  ```
  [submit-requirement "X509-Signed"]
       description = "CMS signed with a valid X.509 certificate"
       applicableIf = is:x509_signed-commit
       submittableIf = is:x509_signed-commit
  [submit-requirement "GPG-Signed"]
       description = "GPG signed with a valid public key"
       applicableIf = is:gpg_signed-commit
       submittableIf = is:gpg_signed-commit
  ```

  The above settings would show **X509-Signed** or **GPG-Signed** if the change has a X.509 or GPG signature associated.
