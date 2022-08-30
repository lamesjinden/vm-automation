(ns scheduling.vbox
  (:require [clojure.core.async :as async]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.spec.alpha :as s])
  (:import (clojure.lang PersistentQueue)))

(s/def ::exit int?)
(s/def ::out-str string?)
(s/def ::out-array bytes?)
(s/def ::out (s/or :string ::out-str
                   :array ::out-array))
(s/def ::err string?)
(s/def ::sh-result (s/keys :req-un [::exit ::out ::err]))

(def vm-states {:running "running"
                :off     "poweroff"})

(def snapshot-on-suffix "running")

(def snapshot-off-suffix "off")

(defn- splitter
  "splits s on spaces, respecting runs of characters within s enclosed by single quotes."
  [s]
  (lazy-seq
   (when-let [c (first s)]
     (cond
       (Character/isSpace c)
       (splitter (rest s))
       (= \' c)
       (let [[w* r*] (split-with #(not= \' %) (rest s))]
         (if (= \' (first r*))
           (cons (apply str w*) (splitter (rest r*)))
           (cons (apply str w*) nil)))
       :else
       (let [[w r] (split-with #(not (Character/isSpace %)) s)]
         (cons (apply str w) (splitter r)))))))

(s/fdef run
  :args string?
  :ret ::sh-result)

(defn- run [command]
  (println (format "$> %s" command))
  (as-> command $
    (splitter $)
    (apply sh $)))

(s/fdef run-throw
  :args (s/cat :command string?)
  :ret ::sh-result
  :fn #(zero? (get-in % [:ret :exit])))

(defn- run-throw
  "runs command and returns the result; 
   
   when result has a non-zero exit code, throws ex-info."
  [command]
  (let [{:keys [exit err out] :as result} (run command)]
    (if (not (zero? exit))
      (throw (ex-info (format "command failed: %s -- %s" command err)
                      {:out out
                       :err err}))
      result)))

(defn- parse-property-line [line]
  (let [tokens (str/split line #"=")]
    (if (not (= 2 (count tokens)))
      nil
      (let [key (get tokens 0)
            value (as-> (get tokens 1) $
                    (.replace $ "\"" ""))]
        {:key   key
         :value value}))))

(defn- parse-state [output]
  (let [lines (str/split-lines output)]
    (->> lines
         (map parse-property-line)
         (filter #(= "VMState" (:key %)))
         (first)
         (:value))))

(defn machine-state
  "returns the state of vm-name as a string. 
   possible return values include: 'running', 'poweroff', 'aborted'"
  [vm-name]
  (let [vminfo-result (run (format "vboxmanage showvminfo --machinereadable %s", vm-name))
        output (:out vminfo-result)
        state (parse-state output)]
    state))

(defn running?
  "returns true if vm-state is running; otherwise false"
  [vm-state]
  (= vm-state (:running vm-states)))

(defn shutdown?
  "returns true if vm-state is poweroff; otherwise false"
  [vm-state]
  (= vm-state (:off vm-states)))

(s/def :snapshot/type #{:snapshot :current-snapshot})
(s/def :snapshot/name string?)
(s/def :snapshot/node string?)
(s/def :snapshot/uuid string?)
(s/def ::snapshot (s/keys :req-un [:snapshot/type
                                   :snapshot/name
                                   :snapshot/node
                                   :snapshot/uuid]))

(defn- ->snapshot [name-kvp uuid-kvp]
  {:type :snapshot
   :node (:key name-kvp)
   :name (:value name-kvp)
   :uuid (:value uuid-kvp)})

(defn- ->current-snapshot [name-kvp uuid-kvp node-kvp]
  {:type :current-snapshot
   :node (:value node-kvp)
   :name (:value name-kvp)
   :uuid (:value uuid-kvp)})

(defn- process-snapshots-success-result [result]
  (let [lines (->> (:out result)
                   (str/split-lines)
                   (map parse-property-line))]
    (loop [queue (into PersistentQueue/EMPTY lines)
           acc []]
      (if (empty? queue)
        acc
        (let [[a b c & _] queue]
          (cond
            (.startsWith (:key a) "Snapshot") (recur
                                               (pop (pop queue))
                                               (conj acc (->snapshot a b)))
            (.startsWith (:key a) "Current") (recur
                                              (pop (pop (pop queue)))
                                              (conj acc (->current-snapshot a b c)))))))))

(defn get-snapshots-command
  "returns the command string used to list the snapshots available for vm-name"
  [vm-name]
  (format "vboxmanage snapshot %s list --machinereadable" vm-name))

(defn get-snapshots
  "returns a sequence of snapshot descriptors (see ->snapshot and ->current-snapshot) for vm-name"
  [vm-name]
  (let [command (get-snapshots-command vm-name)
        {:keys [exit] :as result} (run command)]
    (cond
      (= 1 exit) (list)
      (= 0 exit) (process-snapshots-success-result result) ;todo - normal flow
      :else (throw (ex-info (format "command failed: %s" command)
                            {:out (:out result)
                             :err (:err result)})))))

(defn get-current-snapshot
  "returns the current snapshot descriptor for vm-name;
   if vm-name does not have any snapshots, returns nil."
  ([_vm-name snapshots]
   (->> snapshots
        (filter #(= :current-snapshot (:type %)))
        (first)))
  ([vm-name]
   (get-current-snapshot vm-name (get-snapshots vm-name))))

(defn get-parent-snapshot
  "returns the parent snapshot descriptor for current-snapshot from snapshots"
  [current-snapshot snapshots]
  (let [current-node (:node current-snapshot)]
    (if (or (nil? current-node) (not (re-matches #"^SnapshotName.*-\d+$" current-node)))
      nil
      (let [implied-parent-node (.substring current-node 0 (- (count current-node) 2))]
        (->> snapshots
             (some #(and (= implied-parent-node (:node %)) %)))))))

(defn snapshot-lineage
  "returns a lazy seq of snapshot descriptors representing snapshot lineage.
   the returned seq starts from starting-snapshot and is followed by the 
   parent snapshot, grandparent snapshot, and so on."
  [starting-snapshot snapshots]
  (lazy-seq
   (when (not (nil? starting-snapshot))
     (cons starting-snapshot
           (snapshot-lineage
            (get-parent-snapshot starting-snapshot snapshots) snapshots)))))

(defn delete-recent-shutdown-snapshot!
  "when the current snapshot is on(line), and it's parent snapshot is off(line),
   deletes the parent snapshot."
  [vm-name]
  (let [snapshots (get-snapshots vm-name)
        current-snapshot (get-current-snapshot vm-name snapshots)
        parent-snapshot (get-parent-snapshot current-snapshot snapshots)]
    (cond
      (nil? parent-snapshot) nil

      (and
       (.endsWith (:name current-snapshot) snapshot-on-suffix)
       (.endsWith (:name parent-snapshot) snapshot-off-suffix))
      (let [command (format "vboxmanage snapshot %s delete %s" vm-name (:uuid parent-snapshot))]
        (run-throw command))

      :else nil)))

(defn take-snapshot!
  "takes a snapshot of vm-name. the resulting snapshot is named snapshot-name."
  [vm-name snapshot-name]
  (let [command (format "vboxmanage snapshot %s take %s" vm-name snapshot-name)]
    (run-throw command)))

(defn restore-running-snapshot!
  "when the current snapshot is off(line), and it's parent snapshot is on(line),
   restores vm-name to the parent snapshot.
   
   throws when a parent snapshot does not exist.
   
   throws when snapshot state does not match the nominal case above."
  [vm-name]
  (let [snapshots (get-snapshots vm-name)
        current-snapshot (get-current-snapshot vm-name snapshots)
        parent-snapshot (get-parent-snapshot current-snapshot snapshots)]
    (cond
      (nil? parent-snapshot) nil

      (and
       (.endsWith (:name current-snapshot) snapshot-off-suffix)
       (.endsWith (:name parent-snapshot) snapshot-on-suffix))
      (let [command (format "vboxmanage snapshot %s restore %s" vm-name (:uuid parent-snapshot))]
        (run-throw command))

      :else (throw (ex-info "unexpected snapshot state" {:current-snapshot current-snapshot
                                                         :parent-snapshot  parent-snapshot})))))

(defn get-child-snapshots
  "returns a seq of snapshot descriptors from snapshots that are children snapshot of current-snapshot"
  [current-snapshot snapshots]
  (->> snapshots
       (filter #(= :snapshot (:type %)))
       (filter #(and
                 (.startsWith (:node %) (:node current-snapshot))
                 (not (= (:node %) (:node current-snapshot)))))))

(defn delete-recent-off-snapshot!
  "when the child snapshot of the current snapshot of vm-name is off(line), 
   deletes the child snapshot via run-throw.
    
   throws when there are more than 1 child snapshot.
    
   throws when snapshot state does not match the nominal case above."
  [vm-name]
  (let [snapshots (get-snapshots vm-name)
        current-snapshot (get-current-snapshot vm-name snapshots)
        children-snapshots (get-child-snapshots current-snapshot snapshots)
        child-snapshot (first children-snapshots)]
    (when (> (count children-snapshots) 1)
      (throw (ex-info "current snapshot has more than 1 child snapshots" {:children-snapshots children-snapshots})))

    (cond
      (nil? child-snapshot) nil

      ;; todo
      ;;    v---------------- .endsWith instead?
      (.startsWith (:name child-snapshot) snapshot-off-suffix)
      (let [command (format "vboxmanage snapshot %s delete %s" vm-name (:uuid child-snapshot))]
        (run-throw command))

      :else (throw (ex-info "unexpected child snapshot; expected 'off'" {:child-snapshot child-snapshot})))))

(defn delete-recent-running-snapshot!
  "when the current snapshot is on(line), deletes the current snapshot via run-throw.
   
   when a current snapshot does not exist, returns nil.
   
   when the snapshot state does not match the nominal case above, returns nil."
  [vm-name]
  (let [snapshots (get-snapshots vm-name)
        current-snapshot (get-current-snapshot vm-name snapshots)]
    (cond
      (nil? current-snapshot) nil

      (.endsWith (:name current-snapshot) snapshot-on-suffix)
      (let [command (format "vboxmanage snapshot %s delete %s" vm-name (:uuid current-snapshot))]
        (run-throw command))

      :else nil)))

(s/def ::start-type #{:gui :headless :separate})

(s/fdef start-machine!
  :args (s/cat :vm-name string? :type ::start-type)
  :ret ::sh-result)

(defn start-machine!
  "starts vm-name with type (defaults to 'separate' => detachable)"
  ([vm-name type]
   (let [command (format "vboxmanage startvm %s --type %s" vm-name (name type))]
     (run-throw command)))
  ([vm-name] (start-machine! vm-name :separate)))

(defn stop-machine!
  "(gracefully) stops vm-name using the 'acpipowerbutton' option"
  [vm-name]
  (let [command (format "vboxmanage controlvm %s acpipowerbutton" vm-name)]
    (run command)))

(defn stop-machine-force!
  "(forcefully) stops vm-name using the 'poweroff' option"
  [vm-name]
  (let [command (format "vboxmanage controlvm %s poweroff" vm-name)]
    (run command)))

(defn stop-machine-wait!
  "stops vm-name, waiting up to wait-seconds (defaults to 60 seconds). 
   after wait-seconds, forcefully stops vm-name."
  ([vm-name wait-seconds]
   (let [wait-interval-seconds 1
         wait-interval (* wait-interval-seconds 1000)]
     (stop-machine! vm-name)
     (loop [seconds 1]
       (async/<!! (async/timeout wait-interval))
       (let [current-state (machine-state vm-name)]
         (cond
           (shutdown? current-state) current-state

           (>= (* wait-interval-seconds seconds) wait-seconds) (stop-machine-force! vm-name)

           :else (do
                   (stop-machine! vm-name)
                   (recur (inc seconds))))))))
  ([vm-name]
   (stop-machine-wait! vm-name 60)))

(defn- parse-list-vms [output]
  (let [lines (str/split-lines output)]
    (->> lines
         (map #(str/split % #"\s+"))
         (map (fn [tokens]
                (let [name (.replaceAll (first tokens) "\"" "")
                      id (second tokens)]
                  {:name name
                   :id id}))))))

(defn get-all-vms
  "returns a seq of all virtual machines, regardless of state."
  []
  (let [command "vboxmanage list vms"
        output (:out (run-throw command))
        parsed (parse-list-vms output)]
    parsed))

(defn get-all-running-vms
  "returns a seq of virtual machines in the running state."
  []
  (let [vms (get-all-vms)]
    (->> vms
         (filter #(running? (machine-state (:name %)))))))

(comment

  (get-all-vms)
  (get-all-running-vms)

  (machine-state "vm1")
  (running? (machine-state "vm1"))
  (start-machine! "vm1")
  (start-machine! "vm1" :gui)
  (stop-machine! "vm1")
  (stop-machine-wait! "vm1")

  (machine-state "vm2")
  (start-machine! "vm2" "gui")
  (stop-machine! "vm2")
  (stop-machine-wait! "vm2")

  (get-current-snapshot "vm1")
  (get-current-snapshot "vm2")

  (s/valid? ::snapshot (get-current-snapshot "vm1"))
  (s/explain ::snapshot (get-current-snapshot "vm1"))

  (run-throw "false")
  (require '[clojure.spec.test.alpha :as stest])
  (stest/instrument `run-throw)
  (stest/unstrument `run-throw)
  (stest/instrument `start-machine!)
  
  )