;; Copyright (c) 2019-2020 DINUM, Bastien Guerry <bastien.guerry@data.gouv.fr>
;; SPDX-License-Identifier: EPL-2.0
;; License-Filename: LICENSE

(ns core
  (:require  [cheshire.core :as json]
             [semantic-csv.core :as semantic-csv]
             [clj-http.lite.client :as http]
             [ring.util.codec :as codec]
             [clojure.string :as s]
             [hickory.core :as h]
             [hickory.select :as hs])
  (:gen-class))

(defonce sill-url "https://raw.githubusercontent.com/DISIC/sill/master/2020/sill-2020.csv")
(defonce wikidata-base-url "https://www.wikidata.org/wiki/Special:EntityData/")
(defonce wikidata-base-image-url "https://commons.wikimedia.org/wiki/File:")

;; Keywords to ignore
;; "parent"
;; "formats"
(def sill-mapping {:statut            :s
                   :fonction          :f
                   :contexte-usage    :u
                   :comptoir-du-libre :co
                   :similaire-a       :si
                   :licence           :l
                   :ID                :id
                   :secteur           :se
                   :composant         :c
                   :version           :v
                   :wikidata          :w
                   :nom               :i
                   :groupe            :g
                   :annees            :y})

(def http-get-params {:cookie-policy :standard})

(defn get-sill []
  (map #(clojure.set/rename-keys
         (select-keys % (keys sill-mapping))
         sill-mapping)
       (try (semantic-csv/slurp-csv sill-url)
            (catch Exception e
              (println "Cannot reach SILL csv URL")))))

(defn wd-get-data [entity]
  (when (not-empty entity)
    (-> (try (http/get (str wikidata-base-url entity ".json")
                       http-get-params)
             (catch Exception e
               (println "Cannot reach Wikidata url")))
        :body
        (json/parse-string true)
        :entities
        (as-> e ((keyword entity) e)) ;; kentity
        (select-keys [:claims :descriptions]))))

(defn wc-get-image-url-from-wm-filename [f]
  (if-let [src (try (:body (http/get
                            (str wikidata-base-image-url
                                 (codec/url-encode f "UTF-8"))
                            http-get-params))
                    (catch Exception e
                      (println
                       (str "Can't reach image url for " f))))]
    (let [metas (-> src h/parse h/as-hickory
                    (as-> s (hs/select (hs/tag "meta") s)))]
      (->> metas
           (map :attrs)
           (filter #(= (:property %) "og:image"))
           first
           :content))))

(defn wd-get-first-value [k claims]
  (:value (:datavalue (:mainsnak (first (k claims))))))

;; Other properties to consider:
;; - P178: developer
;; - P275: license
;; - P18: image
;; - P306: operating system (linux Q388, macosx Q14116, windows Q1406)

(defn sill-plus-wikidata []
  (for [entry (get-sill)]
    (-> (if-let [w (not-empty (:w entry))]
          (let [data       (wd-get-data w)
                claims     (:claims data)
                descs      (:descriptions data)
                logo-claim (wd-get-first-value :P154 claims)
                frama      (wd-get-first-value :P4107 claims)]
            (merge entry
                   {:logo    (when (not-empty logo-claim)
                               (wc-get-image-url-from-wm-filename logo-claim))
                    :website (wd-get-first-value :P856 claims)
                    :sources (wd-get-first-value :P1324 claims)
                    :doc     (wd-get-first-value :P2078 claims)
                    :frama   {:encoded-name (codec/form-encode frama "UTF-8")
                              :name         frama}
                    :fr-desc (if-let [d (:value (:fr descs))] (s/capitalize d))
                    :en-desc (if-let [d (:value (:en descs))] (s/capitalize d))}))
          entry)
        (dissoc :w))))

(defn -main [& args]
  (spit "sill.json"
        (json/generate-string
         (sill-plus-wikidata)))
  (println "Updated sill.json"))
