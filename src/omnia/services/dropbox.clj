(ns omnia.services.dropbox
  (:require [omnia
             [db :as db]
             [index :as index]]
            [clojure.string :refer [lower-case split]])
  (:import [com.dropbox.core DbxAppInfo DbxRequestConfig DbxWebAuthNoRedirect DbxClient]
           java.util.Locale
           java.io.ByteArrayOutputStream))

(def auth "TODO: maybe this should just be in the database"
  {:type   :oauth2
   :oauth2 {:start-uri  "https://www.dropbox.com/1/oauth2/authorize?" ;; stupid but whatever
            :token-uri  "https://api.dropboxapi.com/1/oauth2/token"
            :grant_type "authorization_code"}})

(defn get-req-config []
  (DbxRequestConfig. "Omnia" (str (Locale/getDefault))))

(defn get-auth [{:keys [key secret]}]
  (let [app-info (DbxAppInfo. key secret)]
    (DbxWebAuthNoRedirect. (get-req-config) app-info)))

(defn get-auth-init-url [auth] (.start auth))

(defn get-token [auth code]
  (.accessToken (.finish auth code)))

; TODO: should this be reused?
(defn get-client [{:keys [access-token]}]
  (DbxClient. (get-req-config) access-token))

(defn get-file-content [path client]
  (let [stream (ByteArrayOutputStream.)]
    (.getFile client path nil stream)
    (str stream)))

(defn should-get-full-text? [file]
  (or (.endsWith (.path file) ".txt")                       ; TODO: make this much more sophisticated!
      (.endsWith (.path file) ".md")))

(defn should-index? [metadata-entry]
  (and (.isFile metadata-entry)
       (not (some #(.startsWith % ".")
                  (split (.path metadata-entry) #"/")))
       (should-get-full-text? metadata-entry)))             ;; TEMP TEMP Just to speed up full-account indexing

(defn file->doc-with-text
  "Convert a Dropbox file to an Omnia document — with full text.
   TODO: break this into two functions as in Google Drive."
  [client account file]
  (let [f (hash-map :name (.name file)
                    :path (.path file)
                    ;; TODO: include account ID in omnia-id so as to ensure uniqueness and avoid conflicts
                    :omnia-id (lower-case (.path file))     ; lower-case to work around a possible bug in clucy
                    :omnia-account-id (:id account)
                    :omnia-service-name (-> account :service :display-name))]
    (if (should-get-full-text? file)
        (assoc f :text (get-file-content (.path file) client))
        f)))

(defn process-delta-entry! [client account entry]
  (if-let [md (.metadata entry)]
    (if (should-index? md)
        (do
          (println "indexing" (.path md))
          (-> (file->doc-with-text client account md)
              index/add-or-update))
        (println "skipping" (.path md)))
    (index/delete {:omnia-account-id (:id account)
                   :omnia-id         (lower-case (.lcPath entry))})))

(defn synchronize! [{:keys [sync-cursor] :as account}]
  (let [client (get-client account)]
    (loop [cursor sync-cursor]
      (let [;delta (.getDeltaWithPathPrefix client cursor "/Articles")
            delta (.getDelta client cursor)
            ]
        (run! (partial process-delta-entry! client account)
              (.entries delta))

        ; update account cursor in DB
        (db/update-account account :sync-cursor (.cursor delta))

        (Thread/sleep 5)

        ; get more
        (when (.hasMore delta)
          (recur (.cursor delta)))))))
