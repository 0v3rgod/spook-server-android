package com.taylor.spook.core.server

internal object PainelWeb {
    val html = """
        <!doctype html>
        <html lang="pt-BR">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>SPOOK SERVER</title>
          <style>
            :root { color-scheme: dark; --bg:#05070a; --card:#0b1016; --line:#1e2a35; --pink:#ff4d9a; --green:#00c853; --cyan:#20d8f5; --text:#e8f1f5; --muted:#8a8f98; }
            * { box-sizing:border-box; } body { margin:0; background:var(--bg); color:var(--text); font:14px ui-monospace, SFMono-Regular, Menlo, monospace; }
            header { padding:22px max(18px, calc((100vw - 1100px)/2)); border-bottom:1px solid var(--line); display:flex; gap:14px; align-items:center; justify-content:space-between; }
            h1 { margin:0; font-size:18px; letter-spacing:2px; color:var(--pink); } .state { color:var(--green); font-size:12px; }
            main { max-width:1100px; padding:20px 18px 48px; margin:auto; } .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:12px; }
            .card { background:var(--card); border:1px solid var(--line); border-radius:12px; padding:16px; } .card h2 { color:var(--cyan); font-size:12px; letter-spacing:1px; margin:0 0 14px; }
            .value { font-size:23px; font-weight:bold; } .muted { color:var(--muted); } .wide { margin-top:12px; }
            input, button { font:inherit; border-radius:8px; border:1px solid var(--line); padding:10px; background:#05070a; color:var(--text); }
            input { width:100%; } button { cursor:pointer; color:var(--pink); border-color:var(--pink); } button:hover { background:#32101f; }
            form { display:flex; gap:8px; } form input { flex:1; } ul { list-style:none; margin:0; padding:0; max-height:310px; overflow:auto; }
            li { border-top:1px solid var(--line); padding:10px 0; display:flex; gap:8px; justify-content:space-between; } li:first-child { border-top:0; }
            .task { cursor:pointer; } .done { color:var(--muted); text-decoration:line-through; } #login { max-width:480px; margin:9vh auto; } #app { display:none; }
            .login-tabs { display:flex; gap:8px; margin-bottom:12px; }
            .login-tab { flex:1; padding:8px; border:1px solid var(--line); border-radius:8px; background:#05070a; color:var(--text); cursor:pointer; }
            .login-tab.active { border-color:var(--pink); color:var(--pink); background:#32101f; }
            .login-panel { display:none; } .login-panel.active { display:block; }
            @media(max-width:600px) { header { align-items:flex-start; flex-direction:column; } form { flex-direction:column; } }
          </style>
        </head>
        <body>
          <section id="login" class="card">
            <h1>SPOOK SERVER</h1><p class="muted">Painel administrativo local</p>
            <div class="login-tabs">
              <button class="login-tab active" data-tab="senha" onclick="trocarTab('senha')">SENHA</button>
              <button class="login-tab" data-tab="token" onclick="trocarTab('token')">TOKEN</button>
            </div>
            <div id="panel-senha" class="login-panel active">
              <input id="senha" type="password" autocomplete="current-password" placeholder="senha do painel">
              <p><button onclick="entrarSenha()">ENTRAR</button></p>
            </div>
            <div id="panel-token" class="login-panel">
              <input id="token" type="password" autocomplete="current-password" placeholder="token de pareamento (32 hex)">
              <p><button onclick="entrarToken()">ENTRAR</button></p>
            </div>
            <p id="erro" class="muted"></p>
          </section>
          <section id="app">
            <header><h1>SPOOK SERVER</h1><div><span id="online" class="state">\u25cf ONLINE</span> <button onclick="sair()">SAIR</button></div></header>
            <main>
              <div class="grid" id="metricas"></div>
              <div class="grid wide">
                <section class="card"><h2>CHAT</h2><form onsubmit="enviar(event)"><input id="mensagem" placeholder="mensagem"><button>ENVIAR</button></form><ul id="chat"></ul></section>
                <section class="card"><h2>TASKS</h2><form onsubmit="criarTask(event)"><input id="task" placeholder="nova task"><button>CRIAR</button></form><ul id="tasks"></ul></section>
              </div>
              <section class="card wide"><h2>API</h2><span class="muted">Atualiza a cada 5 segundos. Dados ficam no SQLite do celular.</span></section>
            </main>
          </section>
          <script>
            const $ = id => document.getElementById(id);
            let timer;
            const token = () => sessionStorage.getItem('spook-token') || '';
            async function api(path, options={}) {
              const headers = {...(options.headers||{}), Authorization:'Bearer '+token()};
              const r = await fetch(path, {...options, headers});
              if (!r.ok) throw new Error(r.status === 401 ? 'token invalido' : 'erro '+r.status);
              return r.json();
            }
            function esc(v) { const x=document.createElement('span'); x.textContent=v ?? ''; return x.innerHTML; }
            function trocarTab(tab) {
              document.querySelectorAll('.login-tab').forEach(b=>b.classList.toggle('active', b.dataset.tab===tab));
              document.querySelectorAll('.login-panel').forEach(p=>p.classList.toggle('active', p.id==='panel-'+tab));
              $('erro').textContent='';
            }
            async function entrarSenha() {
              const senha = $('senha').value;
              if (!senha) return;
              try {
                const r = await fetch('/api/auth/login', {method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({senha})});
                if (!r.ok) throw new Error('senha invalida');
                const j = await r.json();
                sessionStorage.setItem('spook-token', j.token);
                await entrar();
              } catch(e) { $('erro').textContent=e.message; }
            }
            async function entrarToken() { sessionStorage.setItem('spook-token', $('token').value.trim()); try { await entrar(); } catch(e) { $('erro').textContent=e.message; sessionStorage.removeItem('spook-token'); } }
            async function entrar() { try { await atualizar(); $('login').style.display='none'; $('app').style.display='block'; timer=setInterval(atualizar,5000); } catch(e) { $('erro').textContent=e.message; sessionStorage.removeItem('spook-token'); } }
            function sair() { sessionStorage.removeItem('spook-token'); clearInterval(timer); location.reload(); }
            async function atualizar() { try { const [info,c,t]=await Promise.all([api('/api/info'),api('/api/chat'),api('/api/tasks')]); $('online').textContent='\u25cf ONLINE'; $('metricas').innerHTML=[['HOST',info.host],['REDE',info.rede],['BATERIA',(info.bateria_pct??'?')+'%'],['UPTIME',(info.uptime_s??0)+'s'],['SESSOES',info.sessoes],['ARMAZENAMENTO',(info.mensagens??0)+' msg \u00b7 '+(info.tasks??0)+' tasks']].map(x=>'\u0060<section class="card"><h2>' + x[0] + '</h2><div class="value">' + esc(x[1]) + '</div></section>\u0060').join('\u0027'); $('chat').innerHTML=c.mensagens.map(m=>'\u0060<li><span>' + esc(m.texto) + '</span><small class="muted">' + esc(m.autor||'') + '</small></li>\u0060').join('\u0027')||\u0027<li class="muted">sem mensagens</li>\u0027; $('tasks').innerHTML=t.tasks.map(x=>'\u0060<li class="task ' + (x.feito?'done':'') + '" onclick="alternar(\u0027' + esc(x.id) + '\u0027)"><span>' + (x.feito?'\u2713':'\u25cb') + ' ' + esc(x.titulo) + '</span></li>\u0060').join('\u0027')||\u0027<li class="muted">sem tasks</li>\u0027; } catch(e) { $('online').textContent='\u25cf '+e.message.toUpperCase(); $('online').style.color='#ff3b3b'; throw e; } }
            async function enviar(e) { e.preventDefault(); const el=$('mensagem'); if(!el.value.trim())return; await api('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({texto:el.value})}); el.value=''; atualizar(); }
            async function criarTask(e) { e.preventDefault(); const el=$('task'); if(!el.value.trim())return; await api('/api/tasks',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({titulo:el.value})}); el.value=''; atualizar(); }
            async function alternar(id) { await api('/api/tasks/'+encodeURIComponent(id),{method:'PATCH'}); atualizar(); }
            if(token()) entrar();
          </script>
        </body>
        </html>
    """.trimIndent()
}