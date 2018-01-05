(ns deercreeklabs.tube.server
  (:gen-class)
  (:require
   [clojure.core.async :as ca]
   [clojure.java.io :as io]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu :refer [debugs]]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   [org.httpkit.server :as http]
   [taoensso.timbre :as timbre :refer [debugf errorf infof]])
  (:import
   (java.nio HeapByteBuffer)))

(primitive-math/use-primitive-operators)

(defprotocol ITubeServer
  (start [this] "Start serving")
  (stop [this] "Stop serving")
  (get-conn-count [this] "Return the number of current connections"))

(deftype TubeServer [*conn-count starter *stopper]
  ITubeServer
  (start [this]
    (if @*stopper
      (infof "Server is already started.")
      (reset! *stopper (starter))))

  (stop [this]
    (if-let [stopper @*stopper]
      (do
        (stopper)
        (reset! *stopper nil))
      (infof "Server is not running.")))

  (get-conn-count [this]
    @*conn-count))

(defn make-ws-handler
  [on-connect on-disconnect compression-type *conn-count *conn-id]
  (fn handle-ws [req channel]
    (try
      (let [{:keys [uri remote-addr]} req
            fragment-size 65000 ;; TODO: Figure out this size
            conn-id (swap! *conn-id #(inc (int %)))
            _ (swap! *conn-count #(inc (int %)))
            sender (fn [data]
                     (http/send! channel data))
            closer #(http/close channel)
            conn (connection/make-connection
                  conn-id uri remote-addr on-connect sender closer fragment-size
                  compression-type false)
            on-close  (fn [reason]
                        (swap! *conn-count #(dec (int %)))
                        (on-disconnect conn 1000 reason))
            on-rcv (fn [data]
                     (connection/handle-data conn data))]
        (http/on-receive channel on-rcv)
        (http/on-close channel on-close))
      (catch Exception e
        (errorf "Unexpected exception in handle-ws")
        (lu/log-exception e)))))

(defn make-http-handler [<handle-http http-timeout-ms]
  (fn [req channel]
    (au/go
      (try
        (let [ret-ch (<handle-http (update req :body #(if %
                                                        (slurp %)
                                                        "")))
              timeout-ch (ca/timeout (or http-timeout-ms 1000))
              [ret ch] (au/alts? [ret-ch timeout-ch])]
          (http/send! channel (cond
                                (map? ret) ret
                                (string? ret) {:status 200 :body ret}
                                (= timeout-ch ch) {:status 504 :body ""}
                                :else {:status 500 :body "Bad response"})))
        (catch Exception e
          (errorf "Unexpected exception in http-handler.")
          (lu/log-exception e))))))

(defn <handle-http-test [req]
  (au/go
    (let [{:keys [body]} req]
      (clojure.string/upper-case (slurp body)))))

(defn make-tube-server
  ([port on-connect on-disconnect compression-type]
   (make-tube-server port on-connect on-disconnect compression-type {}))
  ([port on-connect on-disconnect compression-type opts]
   (let [{:keys [<handle-http
                 http-timeout-ms]} opts
         *conn-count (atom 0)
         *stopper (atom nil)
         *conn-id (atom 0)
         ws-handler (make-ws-handler on-connect on-disconnect compression-type
                                     *conn-count *conn-id)
         http-handler (if <handle-http
                        (make-http-handler <handle-http http-timeout-ms)
                        (make-http-handler <handle-http-test 1000))
         handler (fn [req]
                   (http/with-channel req channel
                     (if (http/websocket? channel)
                       (ws-handler req channel)
                       (http-handler req channel))))
         starter (fn []
                   (reset! *stopper (http/run-server handler (u/sym-map port)))
                   (infof "Started server on port %s." port))]
     (->TubeServer *conn-count starter *stopper))))

(defn run-test-server
  ([] (run-test-server 8080))
  ([port]
   (u/configure-logging)
   (let [<handle-http (fn [req]
                        (au/go
                          {:status 200
                           :headers {"content-type" "text/plain"}
                           :body "Yo"}))
         *server (atom nil)
         on-connect (fn [conn]
                      (let [conn-id (connection/get-conn-id conn)
                            uri (connection/get-uri conn)
                            remote-addr (connection/get-remote-addr conn)
                            conn-count (get-conn-count @*server)
                            on-rcv (fn [conn data]
                                     (connection/send
                                      conn (ba/reverse-byte-array data)))]
                        (infof "Opened conn %s on %s from %s. Conn count: %s"
                               conn-id uri remote-addr conn-count)
                        (connection/set-on-rcv conn on-rcv)))
         on-disconnect (fn [conn code reason]
                         (let [conn-id (connection/get-conn-id conn)
                               uri (connection/get-uri conn)
                               remote-addr (connection/get-remote-addr conn)
                               conn-count (get-conn-count @*server)]
                           (infof (str "Closed conn %s on %s from %s. "
                                       "Conn count: %s")
                                 conn-id uri remote-addr conn-count)))
         compression-type :smart
         opts (u/sym-map <handle-http)
         server (make-tube-server port on-connect on-disconnect
                                  compression-type opts)]
     (reset! *server server)
     (start server))))


(defn -main
  [& args]
  (run-test-server))
