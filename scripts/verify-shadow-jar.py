#!/usr/bin/env python3
"""Verify the actual production JAR contains no host-owned Core service implementation."""
import re
import sys
from zipfile import ZipFile

forbidden = re.compile(r"^(?:ru/arc/paper/api/|ru/arc/paper/sql/(?:PaperSqlServiceProvider|SharedPaperSqlService)[^/]*\.class$|ru/arc/paper/redis/(?:PaperRedisServiceProvider|SharedPaperRedisService)[^/]*\.class$|ru/arc/paper/metrics/PaperArcMetricsProvider[^/]*\.class$|ru/arc/paper/runtime/PaperNativeCleanupServiceProvider[^/]*\.class$|org/bukkit/|io/papermc/)")
if len(sys.argv) != 2:
    raise SystemExit("Usage: verify-shadow-jar.py path/to/production.jar")
with ZipFile(sys.argv[1]) as artifact:
    unexpected = sorted(name for name in artifact.namelist() if forbidden.search(name))
    if unexpected:
        raise SystemExit("Production JAR embeds host/server-owned classes:\n" + "\n".join(unexpected))
    descriptor = artifact.read("plugin.yml").decode()
    if "name: ArcEvents" not in descriptor:
        raise SystemExit("Unexpected plugin descriptor")
print("Verified production JAR: " + sys.argv[1])
