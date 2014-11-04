(ns odpr.calculator
  (:require [clj-time.core :as date-core]
            [clj-time.coerce :as date-coerce]
            [clojure.edn :as edn]
            [clojure.string :as string]))

(def ^:private MIN-SERIES-LENGTH 8)

(defn- convert-dates [series]
  (let [data (mapv #(assoc % :date (date-coerce/from-date (:date %))) (:data series))]
    (assoc series :data data)))

(defn- correlation [x y]
  (let [data-x (:data x)
        data-y (:data y)
        date-1-x (:date (first data-x))
        date-2-x (:date (last data-x))
        date-1-y (:date (first data-y))
        date-2-y (:date (last data-y))
        date-1 (date-core/latest date-1-x date-1-y)
        date-2 (date-core/earliest date-2-x date-2-y)
        data-x (filterv (fn [{:keys [date]}] (date-core/within? date-1 date-2 date)) data-x)
        data-y (filterv (fn [{:keys [date]}] (date-core/within? date-1 date-2 date)) data-y)]
    (if (and (>= (count data-x) MIN-SERIES-LENGTH) (= (count data-x) (count data-y)))
      (let [vals-x (mapv :value data-x)
            vals-y (mapv :value data-y)
            n (count vals-x)
            m-x (/ (apply + vals-x) n)
            m-y (/ (apply + vals-y) n)
            xy (map * vals-x vals-y)
            m-xy (/ (apply + xy) n)
            d-x (- (/ (apply + (map #(* % %) vals-x)) n) (* m-x m-x))
            d-y (- (/ (apply + (map #(* % %) vals-y)) n) (* m-y m-y))]
        (if (or (< d-x 1e-6) (< d-y 1e-6))
          0.0
          (let [r (/ (- m-xy (* m-x m-y)) (Math/sqrt (* d-x d-y)))]
            r)))
      0.0)))


(defn- analyze-rs [in-file out-file]
  (let [rs (-> in-file slurp edn/read-string)
        rs (->> rs (filter pos?) sort)
        ss (map str rs)]
    (spit out-file (string/join \newline ss))))

(defn- count-total-corr-pairs-for-factors [in-file]
  (let [data (-> in-file slurp edn/read-string)
        groups (group-by #(+ (* (:factor-id %) 10) (:calendar-level %)) data)
        cs (map count (vals groups))
        cs (map #(* % (- % 1)) cs)]
    (println (apply + cs))))

(defn- count-series-per-cal-level [in-file]
  (let [data (-> in-file slurp edn/read-string)
        groups (group-by :calendar-level data)]
    (doseq [[k v] groups]
      (println k (count v)))))


(defn- calc-rs' [x y]
  (let [factor-x (:factor-id x)
        factor-y (:factor-id y)]
    (when-not (= factor-x factor-y)
      (let [r (correlation x y)]
        (when (> (Math/abs r) 0.9)
          {:id-1 (:id x)
           :id-2 (:id y)
           :f1 factor-x
           :f2 factor-y
           :r r})))))

(defn- calc-rs [x xs rs]
  (doseq [y xs]
    (when-let [r (calc-rs' x y)]
      (println r)
      (swap! rs conj r))))

(defn- calc-for-cal-level [data rs]
  (loop [xs data]
    (when (seq xs)
      (calc-rs (first xs) (rest xs) rs)
      (recur (rest xs)))))

(defn- calculate-correlation-for-all [in-file out-file]
  (let [data (-> in-file slurp edn/read-string)
        data (map convert-dates data)
        groups (group-by :calendar-level data)
        rs (atom [])]
    (doseq [g (vals groups)]
      (calc-for-cal-level g rs))
    (spit out-file (pr-str @rs))
    (println "рядов" (count data))
    (println "корреляций" (count @rs))))


(defn- factor-pair [r]
  (let [f1 (:f1 r)
        f2 (:f2 r)
        s (if (> (:r r) 0) 1 -1)]
    (if (< f1 f2)
      [f1 f2 s]
      [f2 f1 s])))

(defn- exclude-doubles [group]
  (->> group
    (sort-by #(Math/abs (:r %)) >)
    (take 1)))

(defn- exclude-factor-factor-doubles [rs]
  (->> rs
    (group-by factor-pair)
    (vals)
    (mapcat exclude-doubles)))


(defn calculate-correlation [data-dir]
  (calculate-correlation-for-all (str data-dir "/data.edn") (str data-dir "/all-corr.edn")))

(defn prepare-correlation [data-dir]
  (let [rs (-> (str data-dir "/all-corr.edn") slurp edn/read-string)
        factors (-> (str data-dir "/factors.edn") slurp edn/read-string :factors)
        data (-> (str data-dir "/data.edn") slurp edn/read-string)
        unique-rs (exclude-factor-factor-doubles rs)
        unique-factor-ids (->> unique-rs (mapcat (fn [r] [(:f1 r) (:f2 r)])) (into #{}))
        unique-factors (filterv #(get unique-factor-ids (:id %)) factors)
        unique-data (filter #(get unique-factor-ids (:factor-id %)) data)
        unique-data (->> unique-data (map (fn [s] [(:id s) s])) (into {}))]
    (println "используется факторов" (count unique-factor-ids))
    (println "используется рядов" (count unique-data))
    (println "используется корреляций" (count unique-rs))
    (spit (str data-dir "/unique-factors.edn") (pr-str unique-factors))
    (spit (str data-dir "/unique-data.edn") (pr-str unique-data))
    (spit (str data-dir "/unique-corr.edn") (pr-str unique-rs))))
