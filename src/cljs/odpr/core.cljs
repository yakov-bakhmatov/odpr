(ns odpr.core
  (:require [clojure.string :as string]
            [cljs.reader :as edn]
            [om.core :as om :include-macros true]
            [sablono.core :refer-macros [html]]
            [secretary.core :as secretary :refer-macros [defroute]]
            [goog.events :as events])
  (:import goog.History
           goog.history.EventType))

(def ^:private app-state (atom nil)) ; :factors :id-map :groups :current-factor

(defn- parse-int [x]
  (let [n (js/parseInt x)]
    (if (js/isNaN n) nil n)))

(defroute all-factors "/" []
  (swap! app-state dissoc :current-factor :filter1))

(defroute select-factor "/:id" [id]
  (swap! app-state
    (fn [app]
      (let [factor (get (:id-map app) (parse-int id))]
        (assoc app :current-factor factor)))))

(defn- filter-factors [x factors]
  (if (string/blank? x)
    factors
    (let [x (string/lower-case x)]
      (filter (fn [f] (-> f :name string/lower-case (.indexOf x) (>= 0))) factors))))

(defn- main [{:keys [factors groups id-map current-factor filter1 filter2] :as data} owner]
  (om/component
    (if current-factor
      (html
        [:div
          [:div.subtitle
            [:a {:href (all-factors)}
                "Все показатели"]]
          [:div.link [:a {:href "/"} "Случайный график"]]
          [:div.factor1 (:name current-factor)]
          [:div.filter
            [:label "Фильтр: "]
              [:input
                {:type "text" :value filter2
                 :on-change (fn [e]
                              (om/update! data :filter2 (.. e -target -value)))}]]
          [:ul
            (for [f2 (->> current-factor :id (get groups) (map id-map) (filter-factors filter2))]
              [:li
                [:a {:href (str "/factors/" (:id current-factor) "/" (:id f2))
                     :target "_blank"}
                    (:name f2)]])]])
      (html
        [:div
          [:div.subtitle "Все показатели"]
          [:div.link [:a {:href "/"} "Случайный график"]]
          [:div.filter
            [:label "Фильтр: "]
              [:input
                {:type "text" :value filter1
                 :on-change (fn [e]
                              (om/update! data :filter1 (.. e -target -value)))}]]
          [:ul
            (for [factor (filter-factors filter1 factors)]
              (let [id (:id factor)]
                [:li
                  [:a {:href (select-factor {:id id})}
                      (str (:name factor) " [" (count (get groups id)) "]")]]))]]))))

(defn- enable-history []
  (let [h (History.)]
    (goog.events/listen h EventType.NAVIGATE #(secretary/dispatch! (.-token %)))
    (doto h
      (.setEnabled true))))

(defn ^:export init [factors-edn groups-edn]
  (enable-console-print!)
  (let [factors (edn/read-string factors-edn)
        factor-map (->> factors (map (fn [f] [(:id f) f])) (into {}))
        groups (edn/read-string groups-edn)]
    (enable-history)
    (secretary/set-config! :prefix "#")
    (reset! app-state {:factors factors :id-map factor-map :groups groups})
    (om/root main app-state {:target (.getElementById js/document "main")})))
