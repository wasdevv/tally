# frozen_string_literal: true

require "rails_helper"
require "webrick"

# O client contra um servidor HTTP de verdade, nao contra um duble dele.
#
# O que este spec precisa provar e que os BYTES na rede estao certos --
# assinatura, digest, multipart. Um duble de Net::HTTP provaria que o duble
# concorda com o codigo, que e a pergunta errada.
RSpec.describe EngineClient do
  let(:secret) { "0123456789abcdef0123456789abcdef" }
  let(:received) { [] }

  around do |example|
    server = WEBrick::HTTPServer.new(
      Port: 0, Logger: WEBrick::Log.new(File::NULL), AccessLog: []
    )
    server.mount_proc "/" do |req, res|
      received << {
        path: req.path,
        method: req.request_method,
        headers: req.header.transform_values(&:first),
        body: req.body.to_s.b
      }
      res["Content-Type"] = "application/json"
      res.status = @status || 200
      res.body = @response_body || "[]"
    end

    thread = Thread.new { server.start }
    @port = server.config[:Port]
    example.run
    server.shutdown
    thread.join
  end

  def client = described_class.new(base_url: "http://127.0.0.1:#{@port}", secret: secret)

  def signature_of(method, path, digest, timestamp)
    OpenSSL::HMAC.hexdigest("SHA256", secret, "#{timestamp}\n#{method}\n#{path}\n#{digest}")
  end

  it "assina o GET com o digest do corpo vazio" do
    client.batches

    call = received.first
    expected = signature_of("GET", "/api/batches", Digest::SHA256.hexdigest(""),
                            call[:headers]["x-tally-timestamp"])

    expect(call[:headers]["x-tally-signature"]).to eq(expected)
  end

  # O que amarra o ARQUIVO a assinatura: o digest e do conteudo, e o motor o
  # confere contra os bytes que recebeu.
  it "manda o digest do arquivo e assina esse digest" do
    conteudo = "1".ljust(400)
    client.import(io: StringIO.new(conteudo), filename: "itau.ret")

    call = received.first
    digest = Digest::SHA256.hexdigest(conteudo)

    expect(call[:headers]["x-tally-content-sha256"]).to eq(digest)
    expect(call[:headers]["x-tally-signature"])
      .to eq(signature_of("POST", "/api/batches", digest, call[:headers]["x-tally-timestamp"]))
  end

  it "monta o multipart com o arquivo intacto" do
    conteudo = "1".ljust(400)
    client.import(io: StringIO.new(conteudo), filename: "itau-0314.ret")

    body = received.first[:body]
    expect(body).to include('filename="itau-0314.ret"')
    expect(body).to include(conteudo)
  end

  # Retorno bancario vem em ISO-8859-1 e tem acento. Concatenar bytes com texto
  # UTF-8 levantaria Encoding::CompatibilityError no primeiro sacado com cedilha.
  it "aceita arquivo em ISO-8859-1 com acento sem quebrar por encoding" do
    conteudo = "1CONCEICAO".dup.force_encoding("ASCII-8BIT") + [ 0xE7 ].pack("C") + " " * 388

    expect { client.import(io: StringIO.new(conteudo), filename: "a.ret") }.not_to raise_error
    expect(received.first[:body]).to include([ 0xE7 ].pack("C"))
  end

  it "erro do motor vira codigo, nunca frase" do
    @status = 400
    @response_body = '{"code":"FILE_EMPTY","params":{}}'

    expect { client.batches }.to raise_error(EngineClient::Error) { |e|
      expect(e.code).to eq("FILE_EMPTY")
    }
  end

  it "resposta ilegivel vira erro proprio em vez de explodir no parser" do
    @response_body = "isto nao e json"

    expect { client.batches }.to raise_error(EngineClient::Error) { |e|
      expect(e.code).to eq("ENGINE_BAD_RESPONSE")
    }
  end

  it "motor fora do ar vira Unavailable, nao stack trace de socket" do
    fechado = described_class.new(base_url: "http://127.0.0.1:1", secret: secret)

    expect { fechado.batches }.to raise_error(EngineClient::Unavailable)
  end

  it "filtro de estado vai na query, escapado" do
    client.entries(7, status: "NEEDS_REVIEW")

    expect(received.first[:path]).to eq("/api/batches/7/entries")
  end
end
