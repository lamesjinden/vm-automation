(ns scheduling.timing)

(defn should-run?
  "window defines a map of the following keys:
   * start-time: a string that parses to java.time.LocalTime
     * ex. '02:00:00' -> 2 AM LocalTime
   * duration: a string that parses to java.time.Duration
     * ex. 'PT10H' -> 10 hour Duration
   
   now is an instance of java.time.LocalDateTime. 
   now will be evaluated to ensure it falls between start-time + duration."
  ([{:keys [start-time duration] :as _window} now]
   (let [start-datetime (. (java.time.LocalTime/parse start-time) atDate (java.time.LocalDate/now))
         end-datetime (. start-datetime plus (java.time.Duration/parse duration))]
     (println "start-datetime" start-datetime "now" now "end-datetime" end-datetime)
     (and (. start-datetime isBefore now)
          (. now isBefore end-datetime))))
  ([window]
   (when window
     (should-run? window (java.time.LocalDateTime/now)))))