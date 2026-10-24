package com.volei.gateway.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class AutenticacaoFilter implements GlobalFilter, Ordered {
    public static final String HEADER_ID = "X-Usuario-Id";
    public static final String HEADER_NOME = "X-Usuario-Nome";
    public static final String HEADER_PAPEL = "X-Usuario-Papel";

    private static final List<String> HEADERS_DE_IDENTIDADE =
            List.of(HEADER_ID, HEADER_NOME, HEADER_PAPEL);

    private final SecretKey chave;

    public AutenticacaoFilter(@Value("${jwt.secret}") String segredo) {
        this.chave = Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Void> filter(ServerWebExchange troca, GatewayFilterChain cadeia) {
        ServerHttpRequest requisicao = troca.getRequest();

        if (ehRotaPublica(requisicao)) {
            return cadeia.filter(trocaComRequisicao(troca, limparIdentidade(requisicao).build()));
        }

        String autorizacao = requisicao.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (autorizacao == null || !autorizacao.startsWith("Bearer ")) {
            return recusar(troca, "Informe o header Authorization: Bearer <token>");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(chave)
                    .build()
                    .parseSignedClaims(autorizacao.substring("Bearer ".length()))
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return recusar(troca, "Token inválido ou expirado");
        }

        ServerHttpRequest requisicaoAutenticada = limparIdentidade(requisicao)
                .header(HEADER_ID, claims.getSubject())
                .header(HEADER_NOME, claims.get("nome", String.class))
                .header(HEADER_PAPEL, claims.get("papel", String.class))
                .build();

        return cadeia.filter(trocaComRequisicao(troca, requisicaoAutenticada));
    }

    private boolean ehRotaPublica(ServerHttpRequest requisicao) {
        String caminho = requisicao.getURI().getPath();
        boolean ehPost = HttpMethod.POST.equals(requisicao.getMethod());

        return ehPost && ("/auth/login".equals(caminho) || "/auth/registrar".equals(caminho));
    }

    private ServerHttpRequest.Builder limparIdentidade(ServerHttpRequest requisicao) {
        return requisicao.mutate()
                .headers(headers -> HEADERS_DE_IDENTIDADE.forEach(headers::remove));
    }

    private ServerWebExchange trocaComRequisicao(ServerWebExchange troca, ServerHttpRequest requisicao) {
        return troca.mutate().request(requisicao).build();
    }

    private Mono<Void> recusar(ServerWebExchange troca, String mensagem) {
        ServerHttpResponse resposta = troca.getResponse();
        resposta.setStatusCode(HttpStatus.UNAUTHORIZED);
        resposta.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] corpo = ("{\"erro\":\"NAO_AUTENTICADO\",\"mensagem\":\"" + mensagem + "\"}")
                .getBytes(StandardCharsets.UTF_8);

        return resposta.writeWith(Mono.just(resposta.bufferFactory().wrap(corpo)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
