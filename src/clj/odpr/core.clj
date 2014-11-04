(ns odpr.core
  (:require [odpr.loader :as loader]
            [odpr.calculator :as calculator]
            [odpr.webserver :as webserver])
  (:gen-class))

(defn- print-usage []
  (println "arguments: {load-data | prepare-data | calculate | prepare-correlation | serve} [--data data-dir] [--raw-data raw-data-dir]"))

(defn -main [& [command & {data-dir "--data" raw-data-dir "--raw-data" :or {data-dir "data" raw-data-dir "raw-data"}}]]
  (if-not command
    (print-usage)
    (case command
      "load-data" (loader/load-data data-dir raw-data-dir)
      "prepare-data" (loader/prepare data-dir raw-data-dir)
      "calculate" (calculator/calculate-correlation data-dir)
      "prepare-correlation" (calculator/prepare-correlation data-dir)
      "serve" (webserver/serve data-dir)
      (print-usage))))
