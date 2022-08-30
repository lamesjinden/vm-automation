(ns scheduling.shutdown
  (:require [clojure.edn :as edn]
            [scheduling.vbox :as vbox]
            [scheduling.timing :as timing]))

(defn fancy-shutdown [{vm-name :name}]
  (vbox/delete-recent-off-snapshot! vm-name)
  (vbox/delete-recent-running-snapshot! vm-name)
  (vbox/take-snapshot! vm-name vbox/snapshot-on-suffix)
  (vbox/stop-machine-wait! vm-name))

(defn -main [& args]
  (println "shutdown.clj")
  (let [config-file (or (first args) "config.edn")
        config (-> config-file
                   (slurp)
                   (edn/read-string))]
    (->> config
         (filter #(vbox/running? (vbox/machine-state (:name %))))
         (filter #(timing/should-run? (get-in % [:schedule :shutdown-window])))
         (run! fancy-shutdown))))