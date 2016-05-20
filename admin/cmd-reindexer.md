@PLUGIN@ reindex
================

NAME
----
@PLUGIN@ reindex - Start an online reindex

SYNOPSIS
--------
>     ssh -p <port> <host> reindexer start
>      {VERSION}


DESCRIPTION
-----------
Start an online reindex with the specified lucene index version.

The index version for a Gerrit site can be found in
$review_site/index/gerrit_index.config 

ACCESS
------
Any user who has been granted the 'Administrate Server' capability.

SCRIPTING
---------
This command is intended to be used in scripts.

EXAMPLES
--------

Start online reindex with index version 14 (for Gerrit 2.11.x)

>     $ ssh -p 29418 review.example.com reindex start 14

SEE ALSO
--------

* [source] (/admin/reindexer-1.0.groovy)
* [Access Controls](../../../Documentation/access-control.html)
* [Command Line Tools](../../../Documentation/cmd-index.html)

GERRIT
------
Part of [Gerrit Code Review](../../Documentation/index.html)
