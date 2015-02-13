(ns riemann-jmx-clj.core
  (:require [clojure.java.jmx :as jmx]
            [clj-yaml.core :as yaml]
            [riemann.client :as riemann]
            [clojure.pprint :refer (pprint)])
  (:gen-class))

(defn- get-riemann-connection-helper
  [host port]
  (doto (riemann/tcp-client :host host :port port)
    (riemann/connect-client)))

(let [get-riemann-connection-helper (memoize get-riemann-connection-helper)]
  (defn get-riemann-connection
    ([host]
      (get-riemann-connection-helper host 5555))
    ([host port]
      (get-riemann-connection-helper host port))))

(defn run-queries-on-jmx
  "Takes a jmx connection and queries to run, returning a seq of Riemann events."
  [jmx queries]
  (->> (jmx/with-connection jmx
                            (doall
                              (for [{:keys [obj attr tags]} queries
                                    name (jmx/mbean-names obj)
                                    attr attr]
                                {:service (str (.getCanonicalName ^javax.management.ObjectName name) \. attr)
                                 :host    (if (:event_host jmx)
                                            (:event_host jmx)
                                            (:host jmx))
                                 :state   "ok"
                                 :metric  (jmx/read name attr)
                                 :tags    tags})))
       (mapcat (fn [{:keys [service metric] :as event}]
                 (if (map? metric)
                   (for [[k v] metric]
                     (assoc event
                       :service (str service \. (name k))
                       :metric v))
                   [event])))))

(defn run-queries
  "Takes a parsed yaml config and runs the contained queries, returning a seq of Riemann events."
  [yaml]
  (let [{:keys [jmx queries]} yaml]
    (apply concat
      (for [jmx jmx]
        (run-queries-on-jmx jmx queries)))))

(defn run-configuration
  "Takes a parsed yaml config, runs the queries, and posts the results to riemann"
  [yaml]
  (let [{{:keys [host port]} :riemann} yaml
        conn (if port
               (get-riemann-connection host port)
               (get-riemann-connection host))
        events (run-queries yaml)]
    (print ".")
    (flush)
    (riemann/send-events conn events)))

(defn munge-credentials
  "Takes a jmx host spec and, if it has jmx username & password,
   configures the jmx environment map properly. If only a username or
   password is set, exits with an error"
  [host-spec]
  (let [{:keys [username password]} host-spec]
    (when (and username (not password))
      (println "Provided username but no password.")
      (System/exit 1))
    (when (and password (not username))
      (println "Provided password but no username")
      (System/exit 1))
    (if (or username password)
      (assoc (dissoc host-spec :username :password) :environment {"jmx.remote.credentials"
                                                                  (into-array String [username password])})
      host-spec)))

(defn munge-all-credentials
  "Takes a parsed yaml config and prepares credentials for all hosts configs.
  Returnes the yaml with munged hosts, and makes sure hosts is a collection if a single one is specified."
  [config]
  (let [hosts (:jmx config) hosts (if (map? hosts) [hosts] hosts)]
    (assoc config :jmx (map munge-credentials hosts))))

(defn start-config
  "Takes a path to a yaml config, parses it, and runs it in a loop"
  [config]
  (let [yaml (yaml/parse-string (slurp config))
        munged (munge-all-credentials yaml)]
    (pprint munged)
    (future
      (while true
        (try
          (run-configuration munged)
          (Thread/sleep (* 1000 (-> yaml :riemann :interval)))
          (catch Exception e
            (.printStackTrace e)))))))

(defn -main
  [& args]
  (doseq [arg args]
    (start-config arg)
    (println "Started monitors")))
