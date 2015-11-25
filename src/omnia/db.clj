(ns omnia.db
  (require [datomic.api :as d]))

(def ^:private uri "datomic:free://localhost:4334/omnia")   ;; TODO: move to config

(defn ^:private connect [] (d/connect uri))

(def user-schema
  [{:db/id                 (d/tempid :db.part/db)
    :db/ident              :user/email
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "The email address of a User. Long-term I’d rather not rely on email addresses but for now it’s fine."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :user/name
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "The name of the user, i.e. Ada Lovelace."
    :db.install/_attribute :db.part/db}
   ])

(def account-type-schema
  [{:db/id                 (d/tempid :db.part/db)
    :db/ident              :account-type/name
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "Name of the Account Type, e.g. Dropbox"
    :db.install/_attribute :db.part/db}
   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account-type/client-id
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "OAuth 2.0 Client ID for this Account Type for this installation of Omnia"
    :db.install/_attribute :db.part/db}
   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account-type/client-secret
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "OAuth 2.0 Client Secret for this Account Type for this installation of Omnia"
    :db.install/_attribute :db.part/db}
   ])

(def account-schema
  "An Account is a linked Account for a specific User for a given Account Type e.g. Dropbox."
  [{:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/id
    :db/valueType          :db.type/uuid
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "The ID of an Account."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/user
    :db/valueType          :db.type/ref
    :db/cardinality        :db.cardinality/one
    :db/doc                "The User that “owns” this account."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/type
    :db/valueType          :db.type/ref
    :db/cardinality        :db.cardinality/one
    :db/doc                "The type of Account, e.g. Dropbox"
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/access-token
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "OAuth 2.0 Access Token for this Account for this User."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/refresh-token
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "OAuth 2.0 Refresh Token for this Account for this User."
    :db.install/_attribute :db.part/db}

   {:db/id                 (d/tempid :db.part/db)
    :db/ident              :account/sync-cursor
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "Sync cursor value for those systems that use them."
    :db.install/_attribute :db.part/db}
   ])

; just for reference really (at least for now)
;(defn create-db []
;  (d/create-database uri)
;  (d/transact (connect)
;              (concat user-schema account-type-schema account-schema)))

; TBD:
; Does it make sense to isolate Datomic from the rest of the system?
;   Am I adding complexity (transformation) and dulling the advantages of Datomic by doing so?

(defn ^:private remove-namespace-from-map-keys [m]
  (apply hash-map (interleave (map (comp keyword name)
                                   (keys m))
                              (vals m))))

(defn ^:private entity-ref->map [db entity] (->> (d/entity db entity)
                                                 d/touch
                                                 remove-namespace-from-map-keys))

(defn create-account [{:keys [user-email type-name access-token refresh-token]}]
  (as-> {} entity
        (assoc entity
          :db/id (d/tempid :db.part/user)
          :account/id (d/squuid)
          :account/user [:user/email user-email]
          :account/type [:account-type/name type-name]
          :account/access-token access-token)
        (if refresh-token
            (assoc entity :account/refresh-token refresh-token)
            entity)
        (d/transact (connect) [entity])))

(defn get-accounts [user-email]
  (let [db (d/db (connect))
        results (d/q '[:find ?account ?type
                       :in $ ?user-email
                       :where [?user :user/email ?user-email]
                       [?account :account/user ?user]
                       [?type :account-type/name]
                       [?account :account/type ?type]]
                     db user-email)]
    (map (fn [result]
           (-> (entity-ref->map db (first result))
               (assoc :type (entity-ref->map db (second result)))
               (dissoc :user)))
         results)))

(defn update-account [account key value]
  (let [attr (keyword "account" (name key))]
    (d/transact (connect)
                (if (nil? value)
                    [[:db/retract [:account/id (:id account)] attr (key account)]]
                    [{:db/id [:account/id (:id account)]
                      attr   value}]))))