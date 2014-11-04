(ns odpr.webserver
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [compojure.handler :refer [site]]
            [org.httpkit.server :refer [run-server]]
            [clj-time.core :as date-core]
            [clj-time.coerce :as date-coerce]
            [clojure.data.json :as json]
            [clojure.edn :as edn]))

(def ^:private quarters {1 "I" 4 "II" 7 "III" 10 "IV"})

(def ^:private correlation-count (atom nil))

(def ^:private series-count (atom nil))

(def ^:private correlations (atom nil))

(def ^:private series (atom nil))

(def ^:private factors (atom nil))

(def ^:private factor-groups (atom nil))

(defn- parse-int [s]
  (Integer/parseInt s))

(defn- convert-dates [series]
  (let [data (mapv #(assoc % :date (date-coerce/from-sql-date (:date %))) (:data series))]
    (assoc series :data data)))

(defn- pretty-date [date calendar-level]
  (let [year (date-core/year date)
        month (date-core/month date)]
    (if (= calendar-level 3)
      (str (get quarters month month) "." year)
      (str month "." year))))

(defn- get-data-for-correlation [correlation]
  (let [x (convert-dates (get @series (:id-1 correlation)))
        y (convert-dates (get @series (:id-2 correlation)))
        calendar-level (:calendar-level x)
        data-x (:data x)
        data-y (:data y)
        date-1-x (:date (first data-x))
        date-2-x (:date (last data-x))
        date-1-y (:date (first data-y))
        date-2-y (:date (last data-y))
        date-1 (date-core/latest date-1-x date-1-y)
        date-2 (date-core/earliest date-2-x date-2-y)
        data-x (filterv (fn [{:keys [date]}] (date-core/within? date-1 date-2 date)) data-x)
        data-y (filterv (fn [{:keys [date]}] (date-core/within? date-1 date-2 date)) data-y)
        vals-x (mapv :value data-x)
        vals-y (mapv :value data-y)
        dates (mapv :date data-x)]
    {:categories (mapv #(pretty-date % calendar-level) dates)
     :series [{:name (str (:name x) ", " (:area x)) :data vals-x}
              {:name (str (:name y) ", " (:area y)) :data vals-y}]
     :correlation (str "Коэффициент корреляции R = " (:r correlation))}))

(defn- prepare-correlation-data [correlation]
  (-> correlation
    (get-data-for-correlation)
    (json/write-str :escape-unicode false)))

(defn- random-correlation-data []
  (prepare-correlation-data (rand-nth (vals @correlations))))

(defn- my-page [r]
  (str
"<!DOCTYPE HTML>
<html>
	<head>
		<meta http-equiv='Content-Type' content='text/html; charset=utf-8'>
		<title>Ложные корреляции</title>
    <link rel='stylesheet' href='/static/css/odpr.css'/>
		<script type='text/javascript' src='/static/js/jquery.min.js'></script>
    <script type='text/javascript' src='/static/js/highcharts.js'></script>
		<script type='text/javascript'>
$(function () {
    var data = " r ";
    $('#series-1-name').text(data.series[0].name);
    $('#series-2-name').text(data.series[1].name);
    $('#chart').highcharts({
        chart: {
            zoomType: 'xy'
        },
        title: {
            text: ''
        },
        xAxis: [{
            categories: data.categories
        }],
        yAxis: [{ // Primary yAxis
            labels: {
                format: '{value}',
                style: {
                    color: Highcharts.getOptions().colors[0]
                }
            },
            title: {
                text: 'Ряд 1',
                style: {
                    color: Highcharts.getOptions().colors[0]
                }
            },
            opposite: true

        }, { // Secondary yAxis
            gridLineWidth: 0,
            title: {
                text: 'Ряд 2',
                style: {
                    color: Highcharts.getOptions().colors[1]
                }
            },
            labels: {
                format: '{value}',
                style: {
                    color: Highcharts.getOptions().colors[1]
                }
            }

        }],
        tooltip: {
            shared: true
        },
        legend: {
          title: {
            text: data.correlation,
            style: 'font-height: 70%;'
          }
        },
        series: [{
            name: 'Ряд 1',
            type: 'spline',
            data: data.series[0].data
        }, {
            name: 'Ряд 2',
            type: 'spline',
            yAxis: 1,
            data: data.series[1].data
        }]
    });
});


		</script>
	</head>
	<body>
<div class='title'>Открытые данные Пермского края</div>
<div class='subtitle'>Источник - <a href='http://opendata.permkrai.ru/'>http://opendata.permkrai.ru/</a></div>
<div class='link'><a href='/factors/'>Все показатели</a></div>
<div id='chart'></div>
<div class='series-1'>Ряд 1 - <span id='series-1-name'/></div>
<div class='series-2'>Ряд 2 - <span id='series-2-name'/></div>
<div class='link'><a href='/factors/'>Все показатели</a></div>
<div class='count-info'>
Всего рядов " @series-count ", корреляций " @correlation-count
"</div>
	</body>
</html>"
))

(defn- factors-page []
  (str
"<!DOCTYPE HTML>
<html>
	<head>
		<meta http-equiv='Content-Type' content='text/html; charset=utf-8'>
		<title>Ложные корреляции - все показатели</title>
    <link rel='stylesheet' href='/static/css/odpr.css'/>
  </head>
  <body>
    <div id='main'> </div>
    <script type='text/javascript' src='/static/js/react.min.js'></script>
    <script type='text/javascript' src='/static/cljs/odpr.js'></script>
    <script type='text/javascript'>
      var factors='" @factors "';
      var groups='" @factor-groups "';
      odpr.core.init(factors, groups);
    </script>
  </body>
</html>
"))

(defn- pair-page [f1 f2]
  (try
    (let [f1 (parse-int f1)
          f2 (parse-int f2)
          key (if (< f1 f2) [f1 f2] [f2 f1])
          correlation (get @correlations key)]
      (if correlation
        (my-page (prepare-correlation-data correlation))
        (route/not-found "Страница не найдена")))
    (catch Exception e (route/not-found "Страница не найдена"))))

(defroutes all-routes
  (GET "/" [] (my-page (random-correlation-data)))
  (GET "/factors/" [] (factors-page))
  (GET "/factors/:f1/:f2" [f1 f2] (pair-page f1 f2))
  (route/resources "/static")
  (route/resources "/")
  (route/not-found "<p>Page not found.</p>"))

(defn- start-server []
  (run-server
    (site #'all-routes)
    {:port 8080})
  (println "server started"))


(defn- read-data-file [in-file]
  (->> in-file
    (slurp)
    (edn/read-string)))

(defn- factors-pair [r]
  (let [f1 (:f1 r)
        f2 (:f2 r)]
    (if (< f1 f2)
      [f1 f2]
      [f2 f1])))

(defn- prepare-correlations [rs]
  (->> rs
    (map (fn [r] [(factors-pair r) r]))
    (into {})))

(defn- prepare-pairs [factor-map pairs]
  (let [groups (->> pairs
                 (mapcat (fn [[f1 f2]] [{:k f1 :v f2} {:k f2 :v f1}]))
                 (group-by :k))]
    (for [[k v] groups]
      [k (->> v (map :v) (sort-by #(:name (get factor-map %))))])))

(defn serve [data-dir]
  (reset! correlations (prepare-correlations (read-data-file (str data-dir "/unique-corr.edn"))))
  (reset! correlation-count (count @correlations))
  (reset! series (read-data-file (str data-dir "/unique-data.edn")))
  (reset! series-count (count @series))
  (reset! factors (sort-by :name (read-data-file (str data-dir "/unique-factors.edn"))))
  (let [factor-map (->> @factors (map (fn [f] [(:id f) f])) (into {}))]
    (reset! factor-groups (->> (keys @correlations) (prepare-pairs factor-map) (into {}))))
  (start-server)
  )
