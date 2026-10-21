// expect: expecting ')', found ','
//
// Two expressions in a for-loop update clause are Groovy 3 syntax: groovy:4 compiles it, Groovy 2.4 (the
// hub's line) cannot parse it. The first draft of the driver's simple-descriptor parser had one
// (2026-10-12); the hub compile caught it after all 40 groovy:4 checks had passed.
List ids = []
int i = 0
for (int n = 0; n < 3; n++, i += 2) ids << i
