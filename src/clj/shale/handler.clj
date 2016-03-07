(ns shale.handler
  (:require [cheshire [core :as json]]
            [shale.resources :refer [assemble-routes]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [compojure.handler :refer [api]]
            [liberator.dev :as dev]
            [com.stuartsierra.component :as component]))

(defn ignore-trailing-slash
  "From https://gist.github.com/dannypurcell/8215411"
  [handler]
  (fn [request]
    (let [uri (:uri request)]
      (handler (assoc request
                      :uri
                      (if (and
                            (not (= "/" uri))
                            (.endsWith uri "/"))
                          (subs uri 0 (dec (count uri)))
                           uri))))))

(defn user-visible-json-exceptions
  "If you throw an ExceptionInfo, this catches it and sets the response body
  to a JSON object containing the error. In production, this only happens if
  :user-visible is true."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (if (data :user-visible)
            {:body (json/generate-string {"error" (.getMessage e)})
             :status (or (data :status) 500)}
            (if (= "production" (System/getenv "RING_ENV"))
              {:body (json/generate-string {"error" "Unknown error."})
               :status 500}
              (throw e))))))))

(defn state-middleware
  "Add an additional :state key to all requests to inject state into the app."
  [f state]
  (fn [request]
    (f (assoc request :state state))))

(defn build-app [session-pool node-pool]
  (-> (assemble-routes)
      (state-middleware {:session-pool session-pool :node-pool node-pool})
      (dev/wrap-trace :ui :header)
      ignore-trailing-slash
      user-visible-json-exceptions
      api
      wrap-multipart-params))

(defrecord App [ring-app session-pool node-pool]
  component/Lifecycle
  (start [cmp]
    (let [app (build-app session-pool node-pool)]
      (assoc cmp :ring-app app)))
  (stop [cmp]
    (assoc cmp :ring-app nil)))

(defn new-app
  "Construct an uninjected App component."
  []
  (map->App {}))