(defproject odpr "0.1.0"
  :description "open data perm region spurious correlations application"
  :license {:name "MIT"
            :url "http://opensource.org/licenses/MIT"}
  :source-paths ["src/clj"]
  :dependencies
    [[org.clojure/clojure "1.6.0"]

     [org.clojure/data.csv "0.1.2"]
     [org.clojure/data.json "0.2.5"]
     [com.uswitch/clj-soap "0.2.3"]
     
     [clj-time "0.8.0"]
     
     [javax.servlet/servlet-api "2.5"]
     [ring/ring-core "1.3.1"]
     [http-kit "2.1.19"]

     [compojure "1.2.1"]]
  :profiles {:dev {:dependencies
                    [[org.clojure/clojurescript "0.0-2371"]
                     [om "0.7.3"]
                     [sablono "0.2.22"]
                     [secretary "1.2.1"]]}
             :uberjar {:aot :all}}
  :plugins [[lein-ancient "0.5.5"]
            [lein-cljsbuild "1.0.3"]]
  :cljsbuild {:builds {
                :odpr {
                  :source-paths ["src/cljs/odpr"]
                  :compiler {:output-to "resources/public/cljs/odpr.js"
                             :optimizations :advanced
                             :pretty-print false
                             :externs ["react/react.js" "externs/jquery-1.9.js" "externs/twitter-bootstrap.js"]
                             :closure-warnings {:externs-validation :off
                                                :non-standard-jsdoc :off}}}}}
  :main ^:skip-aot odpr.core
  :target-path "target/%s"
  :jvm-opts ["-Duser.timezone=UTC"])
