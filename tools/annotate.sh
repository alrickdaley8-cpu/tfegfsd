#!/usr/bin/env bash
# Pack lines from stdin into GitHub check-run annotations, sized for the two limits that matter:
# a check run shows 10 annotations and each body is truncated around 1024 characters. Grouping a few
# lines per annotation (instead of one) is what keeps a 300-error javac run legible, and cutting each
# line at 220 columns keeps a long Gradle path from eating the whole budget.
#
#   some-command 2>&1 | bash tools/annotate.sh label
#
# Percent signs become %25 and newlines %0A because a `::error::` line is parsed as a workflow command
# before it is ever displayed; a raw newline would end the annotation and the rest would print as log
# noise.
set +e
label="${1:-log}"
awk -v label="$label" '
  function cut(s) { return length(s) > 220 ? substr(s, 1, 217) "..." : s }
  function flush() {
    if (buf != "") {
      # Concatenation with print, not printf: "%0A" inside a printf *format* is a conversion
      # specification and awk rejects it ("improper conversion"), which is precisely the string we
      # need to emit literally.
      print "::error::" label " (" n " line" (n == 1 ? "" : "s") ")" "%0A" buf
      buf = ""; n = 0
    }
  }
  {
    line = $0
    gsub(/\r/, "", line)
    if (line == "") next
    gsub(/%/, "%25", line)
    line = cut(line)
    buf = buf (buf == "" ? "" : "%0A") line
    n++
    if (n == 4) flush()
  }
  END {
    flush()
  }
'
