Local-refdb utility
==============================

COMMANDS
----
`localrefdb count-refs` utility to count the number of refs per project and
register a metric called `num_ref_per_project_<project name>`
`localrefdb sha1-all-refs` utility to combine the SHA1s of all refs into a
new SHA1 and produce a numeric representation of it.
This will also register a metric called:
`sha1_all_refs_per_project_<project name>`


SYNOPSIS
--------
>     ssh -p <port> <host> localrefdb count-refs PROJECT [--verbose]
>     ssh -p <port> <host> localrefdb sha1-all-refs PROJECT [--verbose]

DESCRIPTION
-----------

## Count-refs
A metric generated by a count of all refs in a project except for the
user-edit refs, as these are not usually replicated.

## Sha1-all-refs
A metric generated by combining all SHA1s in a project except for the
user-edit refs, as these are not usually replicated.
The SHA1 is then converted into a numerical value so that a metric can
be registered.

This can be useful to avoid scenarios where refs are different but their
counts match.
In this case, the numerical value provided by this metric will be different
for each node hence highlighting a discrepancy.

This command should be ideally executed during a readonly window,
to avoid ongoing replication tasks affecting the result.

ACCESS
------
Any user who has been granted the 'Administrate Server' capability.

SCRIPTING
---------
This command is intended to be used in scripts.

EXAMPLES
--------
Count refs in project

>     $ ssh -p 29418 review.example.com localrefdb count-refs All-Users

Update global-refdb's sha1

>     $ ssh -p 29418 review.example.com localrefdb sha1-all-refs All-Users
