(ns scheduling.startup
  (:require [clojure.edn :as edn] 
            [scheduling.vbox :as vbox]
            [scheduling.timing :as timing]))

(defn fancy-startup [{vm-name :name
                      start-type :start-type}]
  (vbox/delete-recent-shutdown-snapshot! vm-name)
  (vbox/take-snapshot! vm-name vbox/snapshot-off-suffix)
  (vbox/restore-running-snapshot! vm-name)
  (vbox/start-machine! vm-name start-type))

(defn -main [& args]
  (let [config-file (or (first args) "config.edn")
        config (-> config-file
                   (slurp)
                   (edn/read-string))]
    (->> config
         (map #(assoc % :state (vbox/machine-state (:name %))))
         (filter #(vbox/shutdown? (:state %)))
         (filter #(timing/should-run? (get-in % [:schedule :startup-window])))
         (run! fancy-startup))))
