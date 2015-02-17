(ns shale.nodes
  (:require [clojure.set :refer [difference]]
            [taoensso.carmine :as car :refer (wcar)]
            [schema.core :as s]
            [shale.redis :refer :all]
            [shale.utils :refer :all]
            [clojure.walk :refer :all]
            [shale.configurer :refer [config]]
            [shale.node-pools :as node-pools])
  (:import java.util.UUID
           [shale.node_pools DefaultNodePool AWSNodePool]))

(deftype ConfigNodePool [])

(def node-pool (if (nil? (config :node-pool-impl))
                 (if (nil? (config :node-pool-cloud-config))
                   (node-pools/DefaultNodePool. (or (config :node-list)
                                               ["http://localhost:5555/wd/hub"]))
                   (if (= ((config :node-pool-cloud-config) :provider) :aws)
                     (node-pools/AWSNodePool. (config :node-pool-cloud-config))
                     (throw (ex-info (str "Issue with cloud config: AWS is "
                                          "the only currently supported "
                                          "provider.")
                                     {:user-visible true :status 500}))))
                 (do
                   (extend ConfigNodePool
                     node-pools/INodePool
                       (config :node-pool-impl))
                   (ConfigNodePool.))))

(def node-set-key
  (apply str (interpose "/" [redis-key-prefix "nodes"])))

(def node-key-template
  (apply str (interpose "/" [redis-key-prefix "nodes" "%s"])))

(def node-tags-key-template
  (apply str (interpose "/" [redis-key-prefix "nodes" "%s" "tags"])))

(defn node-key [id]
  (format node-key-template id))

(defn node-tags-key [id]
  (format node-tags-key-template id))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(s/defn node-ids :- [s/Str] []
  (with-car* (car/smembers node-set-key)))

(def NodeInRedis
  "A node, as represented in redis."
  {(s/optional-key :url)   s/Str
   (s/optional-key :tags) [s/Str]})

(def NodeView
  "A node, as presented to library users."
  {(s/optional-key :id)    s/Str
   (s/optional-key :url)   s/Str
   (s/optional-key :tags) [s/Str]})

(s/defn view-model :- NodeView
  [id :- s/Str]
  (let [node-key (node-key id)
        node-tags-key (node-tags-key id)
        [contents tags] (with-car*
                          (car/hgetall node-key)
                          (car/smembers node-tags-key))]
    (keywordize-keys
      (assoc (apply hash-map contents) :tags (or tags []) :id id))))

(s/defn view-models :- [NodeView] []
  (map view-model (node-ids)))

(s/defn view-model-from-url :- NodeView
  [url :- s/Str]
  (first (filter #(= (% :url) url) (view-models))))

(s/defn modify-node :- NodeView
  "Modify a node's url or tags in Redis. Any provided url that's host isn't an
  IP address will be resolved before storing."
  [id {:keys [url tags]
       :or {:url nil
            :tags nil}}]
  (last
    (with-car*
      (let [node-key (node-key id)
            node-tags-key (node-tags-key id)]
        (if url (->> url
                     host-resolved-url
                     str
                     (car/hset node-key :url)))
        (if tags (sset-all node-tags-key tags))
        (car/return (view-model id))))))

(s/defn create-node :- NodeView
  [{:keys [url
           tags]
    :or {:tags []}}]
  (last
    (with-car*
      (let [id (uuid)
            node-key (node-key id)]
        (car/sadd node-set-key id)
        (modify-node id {:url url :tags tags})
        (car/return (view-model id))))))

(s/defn destroy-node [id :- s/Str]
  (with-car*
    (car/watch node-set-key)
    (try
      (let [url (get (view-model id) :url)]
        (if (some #{url} (node-pools/get-nodes node-pool))
          (node-pools/remove-node node-pool url)))
      (finally
        (car/srem node-set-key id)
        (car/del (node-key id))
        (car/del (node-tags-key id)))))
  true)

(defn ^:private to-set [s]
  (into #{} s))

(defn refresh-nodes
  "Syncs the node list with the backing node pool."
  []
  (let [nodes (to-set (node-pools/get-nodes node-pool))
        registered-nodes (to-set (map #(get % :url) (view-models)))]
    (doall
      (concat
        (map #(create-node {:url %})
             (difference nodes registered-nodes))
        (map #(destroy-node ((view-model-from-url %) :id))
             (difference registered-nodes nodes)))))
  true)

(s/defn get-node :- (s/maybe NodeView)
  [{:keys [url
           tags]
    :or {:url nil
         :tags []}
    :as requirements}]
  (let [matches-requirements (fn [model]
                               (apply clojure.set/subset?
                                      (map #(select-keys % [:url :tags])
                                           [requirements model])))]
    (try
      (rand-nth
      (filter matches-requirements
              (view-models)))
      (catch IndexOutOfBoundsException e))))
