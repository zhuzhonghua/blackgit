(defproject blackgit "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/tools.logging "1.3.1" :exclusions [org.slf4j/slf4j-api]]
                 [org.clojure/data.json "2.5.2"]
                 [org.apache.logging.log4j/log4j-api "2.25.3"]
                 [org.apache.logging.log4j/log4j-core "2.25.3"]
                 [org.apache.logging.log4j/log4j-slf4j-impl "2.25.3"]
                 [org.apache.commons/commons-lang3 "3.18.0"]
                 [commons-io/commons-io "2.21.0"]
                 [org.clojure/java.classpath "1.1.1"]
                 [org.clojure/java.jdbc "0.7.12"]
                 [org.xerial/sqlite-jdbc "3.51.3.0"]
                 [com.github.seancorfield/next.jdbc "1.3.1093"]
                 [org.eclipse.jgit/org.eclipse.jgit "6.10.0.202406032230-r"]
                 [com.google.protobuf/protobuf-java "4.35.1"]
                 ]
  :jvm-opts ["-Dclojure.tools.logging.factory=clojure.tools.logging.impl/log4j2-factory"]
  :java-source-paths ["src/java"]
  :resource-paths ["script"]
  :source-paths ["script"]
  :uberjar-exclusions [#"(?:^|/)script/"]
  :main com.blackgit.Main
  :omit-source true
  :repl-options {:init-ns blackgit.core}
  :javac-options ["-target" "11" "-source" "11"]
  )
