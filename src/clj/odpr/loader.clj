(ns odpr.loader
  (:require [clj-soap.core :as soap]
            [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clj-time.core :as date-core]
            [clj-time.format :as date-format]
            [clj-time.coerce :as date-coerce])
  (:import (java.io File)))

;уровень календаря
;1 - год
;2 - полугодие
;3 - квартал
;4 - месяц
;5 - день

(def ^:private CALENDAR-LEVEL-QUARTER 3)
(def ^:private CALENDAR-LEVEL-MONTH 4)

(def ^:private AREA
#{
"Александровский район"
"Бардымский район"
"Березовский район"
"Большесосновский район"
"Верещагинский район"
"Гайнский район"
"Горнозаводский район"
"Гремячинский район"
"Губахинский район"
"Добрянский район"
"Еловский район"
"Ильинский район"
"Карагайский район"
"Кизеловский район"
"Кишертский район"
"Косинский район"
"Кочевский район"
"Красновишерский район"
"Краснокамский район"
"Кудымкарский район"
"Куединский район"
"Кунгурский район"
"Лысьвенский район"
"Нытвенский район"
"Октябрьский район"
"Ординский район"
"Осинский район"
"Оханский район"
"Очерский район"
"Пермский район"
"Сивинский район"
"Соликамский район"
"Суксунский район"
"Уинский район"
"Усольский район"
"Чайковский район"
"Частинский район"
"Чердынский район"
"Чернушинский район"
"Чусовской район"
"Юрлинский район"
"Юсьвинский район"

"Верещагинское городское поселение"
"Горнозаводское городское поселение"
"Гремячинское сельское поселение"
"Ильинское сельское поселение"
"Красновишерское городское поселение"
"Лысьвенское городское поселение"
"Нытвенское городское поселение"
"Октябрьское городское поселение"
"Пашийское сельское поселение"
"Полазненское городское поселение"
"Сарановское сельское поселение"
"Северо-Углеуральское городское поселение"
"Суксунское городское поселение"
"Уральское городское поселение"
"Чайковское городское поселение"
"Чернушинское городское поселение"
"Чусовское городское поселение"
"Широковское сельское поселение"

"Районы г. Перми"

"Дзержинский"
"Индустриальный"
"Кировский"
"Ленинский"
"Мотовилихинский"
"Орджоникидзевский"
"Свердловский"

"ЗАТО Звездный"
"г. Березники"
"г. Кудымкар"
"г. Кунгур"
"г. Пермь"
"г. Соликамск"

"г Оханск"
"г Очер"
"г Усолье"
"пгт Оверята"

"Пермский край"
})

(defn- parse-int [s]
  (Integer/parseInt s))

(defn- parse-double [s]
  (Double/parseDouble (.replace s \, \.)))

(def ^:private date-formatter (date-format/formatter "dd.MM.yyyy"))

(defn- parse-date [s]
  (date-format/parse date-formatter s))

(defn- get-year [date]
  (date-core/year date))

(defn- load-factors-xml []
  (let [client (soap/client-fn "http://opendata.permkrai.ru/LoadDataManager/api/DataPub.asmx?WSDL")]
    (client :GetXMLFactors)))

(defmulti parse-factors-element (fn [elem] (:tag elem)))

(defmethod parse-factors-element :default [elem] [(:tag elem) nil])

(defmethod parse-factors-element :Factors [elem]
  [:factors (mapv #(let [attrs (:attrs %)] {:id (parse-int (:factor_id attrs)) :name (:name attrs)}) (:content elem))])

(defmethod parse-factors-element :Cubes [elem]
  [:cubes (mapv #(let [attrs (:attrs %)] {:id (parse-int (:dim_data_key attrs)) :name (:name attrs)}) (:content elem))])

(defmethod parse-factors-element :FactorCubePairs [elem]
  [:pairs (mapv #(let [attrs (:attrs %)] {:factor-id (parse-int (:factor_id attrs)) :cube-id (parse-int (:dim_data_key attrs))}) (:content elem))])

(defn- parse-factors [s]
  (let [file (File/createTempFile "factors" ".xml")]
    (spit file s)
    (let [data (xml/parse file)
          x (mapv parse-factors-element (:content data))
          result (into {} x)]
      (when-not (.delete file)
        (.deleteOnExit file))
      result)))

(defn load-factors [out-file]
  (let [factors (load-factors-xml)
        parsed-factors (parse-factors factors)]
    (spit out-file (pr-str parsed-factors))))


(defn- load-raw-file [out-dir factor cube]
  (let [file-name (str out-dir "/" factor "-" cube ".csv")]
    (when-not (.exists (io/as-file file-name))
      (let [url (str "http://opendata.permkrai.ru/LoadDataManager/api/getcsv.ashx?factor=" factor "&cube=" cube)
            data (slurp url :encoding "windows-1251")]
        (spit file-name data)))))

(defn- load-raw-data [factors-file raw-data-dir]
  (let [pairs (-> factors-file slurp edn/read-string :pairs)]
    (doseq [p pairs]
      (let [factor (:factor-id p)
            cube (:cube-id p)]
        (when (= cube 690360) ;загружаем только "куб" ОКАТО
          (load-raw-file raw-data-dir factor cube)
          (println "loaded" factor cube))))))


(defn- convert-row [row]
  (let [calendar-level (parse-int (first row))
        date (parse-date (second row))
        value (parse-double (last row))
        area (last (butlast row))
        params [calendar-level area]]
    {:calendar-level calendar-level
     :params params
     :area area
     :date date
     :value value}))

(defn- calendar-level-quarter-or-month [calendar-level]
  (or (= calendar-level CALENDAR-LEVEL-QUARTER) (= calendar-level CALENDAR-LEVEL-MONTH)))

(defn- parse-raw-data [factor-id factor-name raw-data]
  (let [data (csv/read-csv raw-data :separator \;)
        rows (map convert-row (rest data))
        rows (filter #(calendar-level-quarter-or-month (:calendar-level %)) rows)
        groups (group-by :params rows)]
    (for [[k v] groups]
      {:factor-id factor-id
       :calendar-level (:calendar-level (first v))
       :name factor-name
       :area (:area (first v))
       :data (map (fn [x] {:date (:date x) :value (:value x)}) v)})))


(defn- filter-area [data]
  (filter #(AREA (:area %)) data))


(defn- remove-doubles [data]
  (->> data
    (set)
    (sort-by :date)
    (into [])))

(defn- full? [calendar-level data]
  (let [n (count data)]
    (case calendar-level
      2 (= n 2)
      3 (= n 4)
      4 (= n 12)
      true)))

(defn- exclude-partial-periods [calendar-level data]
  (if (or (= calendar-level 1) (= calendar-level 5))
    data
    (let [groups (group-by #(get-year (:date %)) data)
          full (->> (vals groups) (filter #(full? calendar-level %)) (filter identity))
          all (apply concat full)]
      (into [] (sort-by :date all)))))

(defn- continuous? [years]
  (= (+ (- (last years) (first years)) 1) (count years)))

(defn- exclude-not-continuous [data]
  (if (empty? data)
    data
    (let [groups (group-by #(get-year (:date %)) data)
          years (sort (keys groups))]
      (if (continuous? years)
        data
        []))))

(defn- normalize [data]
  (let [values (mapv :value data)]
    (if (apply <= values) ;неубывающая последовательность
      (if (> (last values) (* (first values) 2)) ;последнее значение не менее чем в 2 раз больше первого
        (loop [result []
               data data
               acc 0]
          (if (empty? data)
            result
            (let [x (first data)
                  value (- (:value x) acc)]
              (recur (conj result (assoc x :value value)) (rest data) (+ acc value)))))
        data)
      data)))

;уровень календаря
;1 - год
;2 - полугодие
;3 - квартал
;4 - месяц
;5 - день
(defn- normalize-values [calendar-level data]
  (if (< calendar-level 3)
    data
    (let [groups (group-by #(get-year (:date %)) data)
          normalized (map normalize (vals groups))
          all (apply concat normalized)]
      (into [] (sort-by :date all)))))

(defn- convert-date [data]
  (mapv (fn [x] (assoc x :date (date-coerce/to-date (:date x)))) data))

(defn- prepare-data [data]
  (for [x data]
    (let [calendar-level (:calendar-level x)
          series (->> (:data x)
                    remove-doubles
                    (exclude-partial-periods calendar-level)
                    exclude-not-continuous
                    (normalize-values calendar-level)
                    convert-date)]
      (if (empty? series)
        nil
        (assoc x :data series)))))


(defn- prepare-one-file [pair raw-data-dir factors]
  (let [factor-id (:factor-id pair)
        cube-id (:cube-id pair)
        file-name (str raw-data-dir "/" factor-id "-" cube-id ".csv")]
    (when (.exists (io/as-file file-name))
      (let [factor (get factors factor-id)
            raw-data (slurp file-name)]
        (->> raw-data
          (parse-raw-data factor-id factor)
          (filter-area)
          (prepare-data)
          (filter identity))))))


(defn- extract-area' [file]
  (let [raw-data (slurp file)
        data (csv/read-csv raw-data :separator \;)]
    (->> data
      (map #(last (butlast %)))
      (set))))

(defn- extract-area [in-dir]
  (let [files (->> in-dir (io/as-file) (.listFiles))
        areas (mapcat extract-area' files)]
    (->> areas
      (set)
      (into [])
      (sort)
      (string/join \newline)
      (println))))


(defn- create-dir [name]
  (let [f (io/as-file name)]
    (when-not (.exists f)
      (.mkdirs f))))

(defn load-data [data-dir raw-data-dir]
  (create-dir data-dir)
  (create-dir raw-data-dir)
  (let [factors-file (str data-dir "/factors.edn")]
    (load-factors factors-file)
    (load-raw-data factors-file raw-data-dir)))

(defn prepare [data-dir raw-data-dir]
  (let [factors-data (-> (str data-dir "/factors.edn") slurp edn/read-string)
        pairs (:pairs factors-data)
        factors (->> factors-data :factors (map (fn [f] [(:id f) (:name f)])) (into {}))
        data (mapcat #(prepare-one-file % raw-data-dir factors) pairs)
        data (map (fn [series id] (assoc series :id id)) data (iterate inc 1))]
    (spit (str data-dir "/data.edn") (pr-str data))))
