FROM ruby:3.4-slim AS build
WORKDIR /app
RUN apt-get update -qq && \
    apt-get install --no-install-recommends -y build-essential git && \
    rm -rf /var/lib/apt/lists/*

COPY Gemfile Gemfile.lock ./
RUN bundle config set --local without "development test" && bundle install --quiet

COPY . .
# Assets no build, nao no boot: container que compila CSS ao subir demora para
# atender a primeira requisicao e falha tarde quando falha.
RUN SECRET_KEY_BASE=dummy-for-asset-build bundle exec rails tailwindcss:build assets:precompile

FROM ruby:3.4-slim
WORKDIR /app
RUN apt-get update -qq && \
    apt-get install --no-install-recommends -y curl && \
    rm -rf /var/lib/apt/lists/* && \
    useradd --create-home --shell /usr/sbin/nologin tally

COPY --from=build /usr/local/bundle /usr/local/bundle
COPY --from=build --chown=tally:tally /app /app

USER tally
EXPOSE 3000
CMD ["bundle", "exec", "rails", "server", "-b", "0.0.0.0", "-p", "3000"]
