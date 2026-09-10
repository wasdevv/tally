# frozen_string_literal: true

require "net/http"
require "json"
require "openssl"
require "digest"
require "securerandom"

# O UNICO caminho do console ate o motor.
#
# O console nao tem model espelhando tabela do razao e nao abre conexao no banco
# do motor. Compartilhar banco entre dois servicos e o atalho que vira divida: o
# dia em que o esquema do motor muda, a tela quebra sem ninguem ter tocado nela.
class EngineClient
  class Error < StandardError
    attr_reader :code, :params

    def initialize(code, params = {})
      @code = code
      @params = params
      super("engine error: #{code}")
    end
  end

  # Motor fora do ar e um estado normal de operacao, nao um bug: a tela precisa
  # dizer o que aconteceu e o que fazer, em vez de mostrar 500.
  class Unavailable < Error
    def initialize(reason)
      super("ENGINE_UNAVAILABLE", { "reason" => reason })
    end
  end

  TIMESTAMP_HEADER = "X-Tally-Timestamp"
  SIGNATURE_HEADER = "X-Tally-Signature"
  DIGEST_HEADER = "X-Tally-Content-Sha256"

  def initialize(base_url: ENV.fetch("TALLY_ENGINE_URL", "http://localhost:8080"),
                 secret: ENV.fetch("TALLY_HMAC_SECRET", ""),
                 open_timeout: 2,
                 read_timeout: 15)
    @base_url = URI.parse(base_url)
    @secret = secret
    @open_timeout = open_timeout
    @read_timeout = read_timeout
  end

  def batches = get("/api/batches")

  def batch(id) = get("/api/batches/#{id}")

  def entries(batch_id, status: nil)
    query = status.presence ? "?status=#{ERB::Util.url_encode(status)}" : ""
    get("/api/batches/#{batch_id}/entries#{query}")
  end

  def receivables = get("/api/receivables")

  # `receivable_id` nil e "nenhum destes": a linha vira UNMATCHED e continua no
  # razao. Nao existe descartar linha.
  def decide(batch_id, line, receivable_id)
    body = JSON.dump({ receivableId: receivable_id })
    post("/api/batches/#{batch_id}/entries/#{line}/decision", body,
         Digest::SHA256.hexdigest(body), "application/json")
  end

  def import(io:, filename:)
    bytes = io.read.b
    boundary = "tally#{SecureRandom.hex(8)}"
    body = multipart_body(boundary, bytes, filename)

    # O digest e do ARQUIVO, e vai dentro do material assinado. O motor o
    # confere contra os bytes que recebeu -- e assim a assinatura cobre o
    # conteudo, nao so o envelope.
    digest = Digest::SHA256.hexdigest(bytes)
    post("/api/batches", body, digest, "multipart/form-data; boundary=#{boundary}")
  end

  private

  # `String#b` em cada pedaco: o arquivo e bytes, e concatenar bytes com texto
  # UTF-8 levanta Encoding::CompatibilityError no primeiro retorno com acento.
  def multipart_body(boundary, bytes, filename)
    header = "--#{boundary}\r\n" \
      "Content-Disposition: form-data; name=\"file\"; filename=\"#{filename}\"\r\n" \
      "Content-Type: application/octet-stream\r\n\r\n"

    header.b + bytes + "\r\n--#{boundary}--\r\n".b
  end

  def get(path) = request(Net::HTTP::Get.new(path), path, "GET", empty_digest)

  def post(path, body, digest, content_type)
    req = Net::HTTP::Post.new(path)
    req.body = body
    req["Content-Type"] = content_type
    req[DIGEST_HEADER] = digest
    request(req, path, "POST", digest)
  end

  def request(req, path, method, digest)
    timestamp = Time.now.to_i.to_s
    req[TIMESTAMP_HEADER] = timestamp
    req[SIGNATURE_HEADER] = sign(method, path, digest, timestamp)

    response = Net::HTTP.start(@base_url.host, @base_url.port,
                               open_timeout: @open_timeout, read_timeout: @read_timeout) do |http|
      http.request(req)
    end

    parse(response)
  rescue Errno::ECONNREFUSED, Net::OpenTimeout, Net::ReadTimeout, SocketError => e
    raise Unavailable, e.class.name
  end

  def sign(method, path, digest, timestamp)
    material = "#{timestamp}\n#{method}\n#{path}\n#{digest}"
    OpenSSL::HMAC.hexdigest("SHA256", @secret, material)
  end

  def parse(response)
    body = response.body.to_s
    payload = body.empty? ? nil : JSON.parse(body)

    return payload if response.code.to_i < 400

    # O motor devolve codigo, nunca frase. O console e quem tem os idiomas.
    if payload.is_a?(Hash) && payload["code"]
      raise Error.new(payload["code"], payload["params"] || {})
    end

    raise Error.new("ENGINE_ERROR", { "status" => response.code })
  rescue JSON::ParserError
    raise Error.new("ENGINE_BAD_RESPONSE", { "status" => response.code })
  end

  # GET nao tem corpo, e o digest de corpo vazio e constante -- o motor calcula
  # o mesmo do lado dele.
  def empty_digest = Digest::SHA256.hexdigest("")
end
