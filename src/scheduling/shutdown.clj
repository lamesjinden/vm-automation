(ns scheduling.shutdown
  (:require [clojure.edn :as edn]
            [scheduling.vbox :as vbox]))

(defn fancy-shutdown [{vm-name :name}]
  (vbox/delete-recent-off-snapshot! vm-name)
  (vbox/delete-recent-running-snapshot! vm-name)
  (vbox/take-snapshot! vm-name vbox/snapshot-on-suffix)
  (vbox/stop-machine-wait! vm-name))

(defn -main [& args]
  (let [config-file (or (first args) "config.edn")
        config (-> config-file
                   (slurp)
                   (edn/read-string))]
    (->> (get-in config [:virtual-machines])
         (map #(assoc % :state (vbox/machine-state (:name %))))
         (filter #(vbox/running? (:state %)))
         (run! fancy-shutdown))))