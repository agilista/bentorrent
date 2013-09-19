BenTorrent, a fork of Ttorrent
==============================

The original code is at: https://github.com/turn/ttorrent

**Ttorrent** is a pure-Java implementation of the BitTorrent protocol, providing a BitTorrent tracker, a BitTorrent
client and the related Torrent metainfo files creation and parsing capabilities.

BenTorrent removes all the dependencies except for simple embedded server by copying over the source for some commons.io
stuff and have stubbed out the slf4j logging bit (still to do properly).


**Why bother to remove a coupe of lib files and repackage?**

Well, this restful client / seeder / leecher component based on Ttorrent will eventually become part of a build system
called Ben (Build ENtertainment)

It is intended to back the dependency resolution part with peer sharing of resources (such as lib files), and to
basically have a much better resource resolution that the crappy tools in Java (i.e. maven)

It therefore makes sense to seriously limit deps in a build system.



Next Bits in the Pipeline
=========================

* 1 command provisioning of any machine for Tracker capabilities
* There's not very many tests of the code that's been pulled in from Ttorrent, so more will be done.
* BenTracker will be backed by a mvnrepo sync feature so that there will always be one seeder in the swarm for resources
* Eventually a publicly available seeder will be made available
* The ant build will be replaced by a Ben one
* Simple will be replaced by something a bit more current.  That will be blind open heart surgery without more tests, so they come first
* A few more restful services will be exposed in order to tie back to Ben


As Ttorrent, Apache, and SLF files copied are Apache 2, this is as well.



