// Gera credenciais temporárias do TURN da Cloudflare para o TRC TELAS.
// A chave secreta fica nas variáveis de ambiente da Vercel e nunca vai para o navegador.
export default async function handler(req, res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Cache-Control', 'no-store');
  const keyId = process.env.CF_TURN_KEY_ID, token = process.env.CF_TURN_TOKEN;
  if (!keyId || !token) return res.status(503).json({ error: 'turn-not-configured' });
  try {
    const r = await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${keyId}/credentials/generate-ice-servers`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ ttl: 86400 })
    });
    if (!r.ok) return res.status(502).json({ error: 'turn-upstream', status: r.status });
    const data = await r.json();
    // Remove as URLs na porta 53, que alguns navegadores bloqueiam.
    const iceServers = (data.iceServers || []).map(s => ({
      ...s,
      urls: [].concat(s.urls).filter(u => !/:53(\?|$)/.test(u))
    }));
    return res.status(200).json({ iceServers });
  } catch {
    return res.status(502).json({ error: 'turn-failed' });
  }
}
