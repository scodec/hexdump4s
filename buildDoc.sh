#!/bin/bash
cs launch org.scalameta:mdoc_3:2.3.2 -- --in README.template.md --out README.md --classpath $(cs fetch --classpath org.scodec:scodec-bits_3:1.1.33):$(cs fetch --classpath org.scodec:scodec-core_3:2.1.0) $*
