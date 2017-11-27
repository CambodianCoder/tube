(ns deercreeklabs.tube.client
  (:refer-clojure :exclude [send])
  (:require
   [clojure.core.async :as ca]
   [deercreeklabs.async-utils :as au]
   [deercreeklabs.baracus :as ba]
   [deercreeklabs.log-utils :as lu]
   [deercreeklabs.tube.connection :as connection]
   [deercreeklabs.tube.utils :as u]
   #?(:clj [gniazdo.core :as ws])
   #?(:cljs [goog.object])
   [schema.core :as s :include-macros true]
   [taoensso.timbre :as timbre
    #?(:clj :refer :cljs :refer-macros) [debugf errorf infof]])
  #?(:clj
     (:import
      (java.net ConnectException URI))))

#?(:cljs
   (set! *warn-on-infer* true))

#?(:clj
   (primitive-math/use-primitive-operators))

(def default-keepalive-secs 25)

(defn start-keep-alive-loop [conn keep-alive-secs *shutdown]
  (au/go
    (while (not @*shutdown)
      (ca/<! (ca/timeout (* 1000 (int keep-alive-secs))))
      ;; check again in case shutdown happened while we were waiting
      (when-not @*shutdown
        (connection/send-ping conn)))))

(defprotocol ITubeClient
  (send [this data] "Send binary bytes over this tube")
  (close [this] [this code reason] "Close this tube"))

(deftype TubeClient [conn]
  ITubeClient
  (send [this data]
    (connection/send conn data))

  (close [this]
    (connection/close conn)))

#?(:clj
   (defn <make-ws-client-clj
     [uri connected-ch on-error *handle-rcv *close-client]
     (ca/go
       (try
         (let [fragment-size 31999
               on-bin (fn [bs offset length]
                        (let [data (ba/slice-byte-array
                                    bs offset (+ (int offset) (int length)))]
                          (@*handle-rcv data)))
               socket (ws/connect
                       uri
                       :on-close (fn [code reason]
                                   (@*close-client code reason))
                       :on-error on-error
                       :on-binary on-bin
                       :on-connect (fn [session]
                                     (ca/put! connected-ch true)))
               closer #(ws/close socket)
               sender (fn [data]
                        (try
                          ;; Send-msg mutates binary data, so we make a copy
                          (ws/send-msg socket (ba/slice-byte-array data))
                          (catch Exception e
                            (on-error
                             (lu/get-exception-msg-and-stacktrace e)))))]
           (u/sym-map sender closer fragment-size))
         (catch ConnectException e ;; Ignore connection exceptions
           nil)))))

#?(:cljs
   (defn <make-ws-client-node
     [uri connected-ch on-error *handle-rcv *close-client]
     (au/go
       (let [fragment-size 32000
             WSC (goog.object.get (js/require "websocket") "client")
             ^js/WebSocketClient client (WSC.)
             *conn (atom nil)
             msg-handler (fn [msg-obj]
                           (let [data (-> (goog.object.get msg-obj "binaryData")
                                          (js/Int8Array.))]
                             (@*handle-rcv data)))
             closer #(if @*conn
                       (.close ^js/WebSocketConnection @*conn)
                       (.abort client))
             sender (fn [data]
                      (.sendBytes ^js/WebSocketConnection @*conn
                                  (js/Buffer. data)))
             conn-handler (fn [^js/WebSocketConnection conn]
                            (.on conn "close" (fn [code reason]
                                                (@*close-client code reason)))
                            (.on conn "error" on-error)
                            (.on conn "message" msg-handler)
                            (reset! *conn conn)
                            (ca/put! connected-ch true))
             failure-handler  (fn [err]
                                (let [reason [:connect-failure
                                              (lu/get-exception-msg err)]]
                                  (on-error reason)))]
         (.on client "connectFailed" failure-handler)
         (.on client "connect" conn-handler)
         (.connect ^js/WebSocketClient client uri)
         (u/sym-map sender closer fragment-size)))))

#?(:cljs
   (defn <make-ws-client-browser
     [uri connected-ch on-error *handle-rcv *close-client]
     (au/go
       (let [fragment-size 32000
             client (js/WebSocket. uri)
             msg-handler (fn [msg-obj]
                           (let [data (js/Int8Array. (.-data msg-obj))]
                             (@*handle-rcv data)))
             closer #(.close client)
             sender (fn [data]
                      (.send client (.-buffer data)))]
         (set! (.-binaryType client) "arraybuffer")
         (set! (.-onopen client) (fn [event]
                                   (ca/put! connected-ch true)))
         (set! (.-onclose client) (fn [event]
                                    (@*close-client (.-code event)
                                     (.-reason event))))
         (set! (.-onerror client) on-error)
         (set! (.-onmessage client) msg-handler)
         (u/sym-map sender closer fragment-size)))))

#?(:cljs
   (defn <make-ws-client-jsc-ios
     [uri connected-ch on-error *handle-rcv *close-client]
     ;; TODO: Implement jsc-ios
     ))

(defn <make-ws-client [& args]
  (let [factory #?(:clj <make-ws-client-clj
                   :cljs (case (u/get-platform-kw)
                           :node <make-ws-client-node
                           :jsc-ios <make-ws-client-jsc-ios
                           :browser <make-ws-client-browser))]
    (apply factory args)))

(defn <make-tube-client [uri connect-timeout-ms options]
  "Will return a connected client or a closed channel (nil) on timeout"
  (au/go
    (let [{:keys [compression-type keep-alive-secs on-disconnect on-rcv]
           :or {compression-type :smart
                keep-alive-secs default-keepalive-secs
                on-disconnect (constantly nil)
                on-rcv (constantly nil)}} options
          *handle-rcv (atom nil)
          *close-client (atom nil)
          *shutdown (atom false)
          connected-ch (ca/chan)
          ready-ch (ca/chan)
          on-error (fn [msg]
                     (errorf "Error in websocket: %s" msg)
                     (@*close-client 1011 msg))
          wsc (au/<? (<make-ws-client uri connected-ch on-error *handle-rcv
                                      *close-client))
          {:keys [sender closer fragment-size]} wsc
          close-client (fn [code reason]
                         (reset! *shutdown true)
                         (when closer
                           (closer))
                         (on-disconnect code reason))
          path uri ;; TODO: parse to get actual path?
          on-connect (fn [conn conn-id path]
                       (ca/put! ready-ch true))
          conn (connection/make-connection uri on-connect path sender closer nil
                                           compression-type true on-rcv)
          _ (reset! *handle-rcv #(connection/handle-data conn %))
          _ (reset! *close-client close-client)
          [connected? ch] (ca/alts! [connected-ch
                                     (ca/timeout connect-timeout-ms)])]
      (when (= connected-ch ch)
        (sender (ba/encode-int fragment-size))
        (loop []
          (when-not @*shutdown
            (let [[ready? ch] (ca/alts! [ready-ch (ca/timeout 100)])]
              (if (= ready-ch ch)
                (do
                  (start-keep-alive-loop conn keep-alive-secs *shutdown)
                  (->TubeClient conn))
                ;; Wait for the protocol negotiation to happen
                (recur)))))))))
