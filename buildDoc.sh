#!/bin/bash
cs launch org.scalameta:mdoc_2.13:2.3.2 -- --in README.template.md --out README.md --classpath $(cs fetch --classpath org.scodec::scodec-bits:1.1.33) $*
