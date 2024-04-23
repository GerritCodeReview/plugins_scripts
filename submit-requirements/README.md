Custom Submit Requirements
=============================

Overview
--------
Scripts for adding custom [submit requirements](https://gerrit-documentation.storage.googleapis.com/Documentation/3.9.4/config-submit-requirements.html) for Gerrit

Index
-----
* [signed-commit.groovy](signed-commit.groovy) Adds the `is:x509-signed-commit` and `is:gpg-signed-commit`
  custom submit requirements for checking if the latest patch-set is digitally signed or not with either X.509
  (S/MIME) or GnuPG keys.