Rails.application.routes.draw do
  root "batches#index"

  resources :batches, only: %i[index show]
  resources :receivables, only: :index
  resource :import, only: %i[new create]
  resource :locale, only: :update

  get "up" => "rails/health#show", as: :rails_health_check
end
