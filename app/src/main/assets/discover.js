'use strict';
window.Discovery=(()=>{
 let kind='song',entities=[],busy=false,failures=[],moreSources=new Set(),sourceOffsets={},entityOffset=0,entityMore=false,homeTab='recommend',homeSerial=0,detailSerial=0;
 let historySources=null,homeMemory={};
 const labels={song:'单曲',artist:'歌手',playlist:'歌单'};
 function node(tag,cls,text){const el=document.createElement(tag);if(cls)el.className=cls;if(text!==undefined)el.textContent=text;return el;}
 function button(label,cls,fn){const b=node('button',cls,label);b.onclick=fn;return b;}
 function cleanOnline(rows){const ids=new Set();return(Array.isArray(rows)?rows:[]).filter(x=>x&&x.source==='netease'&&/^\d+$/.test(String(x.id))&&typeof x.name==='string').slice(0,100).map(x=>({source:'netease',kind:'playlist',id:String(x.id),name:x.name.slice(0,150),cover:/^https:\/\//.test(x.cover||'')?x.cover:'',subtitle:'收藏的在线歌单'})).filter(x=>{if(ids.has(x.id))return false;ids.add(x.id);return true;});}
 function setKind(next,run=true){if(!labels[next])return;kind=next;$('search-kind').value=next;
  if(next!=='song'){if(!historySources)historySources=[...selectedSources];selectedSources=new Set(['netease']);}else if(historySources){selectedSources=new Set(historySources);historySources=null;}
  document.querySelectorAll('[data-source]').forEach(b=>{b.disabled=next!=='song';b.classList.toggle('selected',selectedSources.has(b.dataset.source));});$('catalog-source-note').classList.toggle('hidden',next==='song');
  searchTicket++;busy=false;searching=false;results=[];entities=[];moreSources.clear();entityMore=false;
  if(run&&$('keyword').value.trim())search(false);else if(!$('keyword').value.trim())showHome();else render();
 }
 function showHome(){++searchTicket;lastKeyword='';busy=false;searching=false;results=[];entities=[];failures=[];$('search-results').replaceChildren();$('welcome').classList.remove('hidden');$('results-heading').textContent='发现音乐';loadHome();}
 async function search(append=false){if(append&&$('keyword').value.trim()!==lastKeyword)append=false;const kw=$('keyword').value.trim();if(!kw){showHome();return;}if(append&&busy)return;
  const ticket=++searchTicket;lastKeyword=kw;busy=true;searching=true;failures=[];$('welcome').classList.add('hidden');$('results-heading').textContent=labels[kind]+'搜索结果';
  if(!append){results=[];entities=[];entityOffset=0;entityMore=false;sourceOffsets={};moreSources=new Set(selectedSources);}
  render();
  if(kind==='song'){
   const sources=append?[...moreSources]:[...selectedSources];
   await Promise.all(sources.map(async source=>{const off=sourceOffsets[source]||0;
    try{const rows=await native('search',{source,keyword:kw,limit:20,offset:off});if(ticket!==searchTicket)return;if(!Array.isArray(rows))throw new Error('搜索响应异常');const ids=new Set(results.map(t=>t.uid));let added=0;rows.slice(0,20).forEach(t=>{if(t&&t.uid&&!ids.has(t.uid)){results.push(t);ids.add(t.uid);added++;}});sourceOffsets[source]=off+20;if(rows.length<20||added===0||sourceOffsets[source]>=2000)moreSources.delete(source);}
    catch(e){if(ticket!==searchTicket)return;failures.push(sourceNames[source]+'：'+e.message);}
   }));
  }else{
   try{const r=await native('catalog',{operation:'search',kind,keyword:kw,offset:entityOffset});if(ticket!==searchTicket)return;if(!Array.isArray(r.items))throw new Error('搜索响应异常');const ids=new Set(entities.map(e=>e.id));r.items.forEach(e=>{if(!ids.has(e.id)){entities.push(e);ids.add(e.id);}});entityOffset=r.offset;entityMore=!!r.hasMore;}
   catch(e){if(ticket!==searchTicket)return;failures.push('网易云：'+e.message);}
  }
  if(ticket!==searchTicket)return;busy=false;searching=false;render();
 }
 function render(){if(!lastKeyword)return;const box=$('search-results');box.setAttribute('aria-busy',String(busy));box.replaceChildren();
  const rows=kind==='song'?results:entities;
  if(rows.length){const list=node('div');box.append(list);if(kind==='song')trackList(list,results);else entityList(list,entities,false);}
  else if(!busy&&!failures.length)empty(box,'暂时没有找到'+labels[kind],'换个关键词再试试。','search');
  if(failures.length){const err=node('div','catalog-error');err.append(node('strong','',rows.length?'部分来源暂不可用':'搜索服务暂时不可用'),node('p','',failures.join('；')),button('重试搜索','secondary',()=>search(rows.length>0)));box.append(err);}
  if(busy)box.append(node('p','loading-row','正在搜索…'));
  else if((kind==='song'?moreSources.size:entityMore)&&rows.length)box.append(button('加载更多','catalog-more',()=>search(true)));
 }
 function image(entity){const wrap=node('span','catalog-cover');wrap.innerHTML=icon(entity.kind==='artist'?'music':'folder');if(/^https:\/\//.test(entity.cover||'')){const img=node('img');img.src=entity.cover;img.alt='';img.loading='lazy';img.referrerPolicy='no-referrer';img.onerror=()=>img.remove();wrap.append(img);}return wrap;}
 function entityList(box,rows,grid){box.replaceChildren();if(grid)box.classList.add('catalog-grid');rows.forEach(e=>{const b=node('button',grid?'catalog-tile':'catalog-row');const text=node('span','catalog-meta');text.append(node('strong','',e.name),node('small','',e.subtitle||'网易云'));b.append(image(e),text);b.onclick=()=>openEntity(e);box.append(b);});}
 async function loadHome(refresh=false){const serial=++homeSerial,operation=homeTab,box=$('recommend-content');$('recommend-playlists').classList.toggle('selected',operation==='recommend');$('recommend-hot').classList.toggle('selected',operation==='hot');
  if(homeMemory[operation]&&!refresh){drawHome(homeMemory[operation],operation);return;}
  box.classList.remove('catalog-grid');box.replaceChildren(node('p','loading-row','正在获取推荐…'));
  try{const r=await native('catalog',{operation,offset:0,...(refresh?{refresh:true}:{})});if(serial!==homeSerial)return;homeMemory[operation]=r;drawHome(r,operation);}
  catch(e){if(serial!==homeSerial)return;box.replaceChildren(node('p','', '推荐暂不可用：'+e.message),button('重新加载','secondary',()=>loadHome(true)));}
 }
 function drawHome(r,operation){const box=$('recommend-content');box.classList.remove('catalog-grid');if(operation==='recommend'){if(!Array.isArray(r.items)||!r.items.length){empty(box,'暂无推荐歌单','可以先搜索喜欢的音乐。');return;}entityList(box,r.items,true);}else{if(!Array.isArray(r.tracks)||!r.tracks.length){empty(box,'暂无热门歌曲','可以先搜索喜欢的音乐。');return;}trackList(box,r.tracks);}$('welcome').classList.toggle('hidden',!!lastKeyword);}
 function saved(e){return(library.onlinePlaylists||[]).some(x=>x.id===e.id&&x.source===e.source);}
 function toggleOnline(e){if(saved(e))library.onlinePlaylists=library.onlinePlaylists.filter(x=>x.id!==e.id);else library.onlinePlaylists=cleanOnline([...(library.onlinePlaylists||[]),e]);save();renderLibrary();toast(saved(e)?'已收藏在线歌单':'已取消收藏');}
 function onlineLibrary(box){(library.onlinePlaylists||[]).forEach(e=>{const row=node('div','playlist-card');const tile=node('span','tile');tile.innerHTML=icon('folder');const meta=node('button','meta');meta.append(node('strong','',e.name),node('small','','网易云 · 在线歌单'));meta.onclick=()=>openEntity(e);const menu=button('','icon-btn',()=>{const b=openModal(e.name);option(b,'打开在线歌单','folder',()=>openEntity(e));option(b,'取消收藏在线歌单','trash',()=>{toggleOnline(e);closeModal();},true);});menu.innerHTML=icon('more');menu.setAttribute('aria-label','在线歌单操作 '+e.name);row.append(tile,meta,menu);box.append(row);});}
 function openEntity(entity){const serial=++detailSerial;const box=openModal(entity.name);$('modal').dataset.context='catalog-'+serial;let tracks=[],offset=0,hasMore=true,loading=false,description='',notice='';let resultEntity=entity;
  const alive=()=>serial===detailSerial&&$('modal').dataset.context==='catalog-'+serial&&!$('modal').classList.contains('hidden');
  const body=node('div','catalog-detail');box.append(body);
  function draw(error){const sheet=document.querySelector('.sheet'),top=sheet.scrollTop;body.replaceChildren();const actions=node('div','detail-actions');
   if(tracks.length)actions.append(button(hasMore?'播放已加载歌曲':'播放全部','primary',()=>play(tracks,0)));
   if(entity.kind==='playlist'){actions.append(button(saved(entity)?'取消收藏歌单':'收藏在线歌单','secondary',()=>{toggleOnline(resultEntity);draw();}));if(tracks.length)actions.append(button('将已加载曲目存为本地歌单','text-button',()=>{const pl={id:'pl-online-'+Date.now(),name:resultEntity.name,tracks:tracks.map(cleanTrack).filter(Boolean)};library.playlists.push(pl);save();renderLibrary();toast('已保存 '+pl.tracks.length+' 首歌曲');}));}
   body.append(node('p','catalog-detail-caption',description||'正在获取内容…'),actions);if(notice)body.append(node('p','catalog-source-note',notice));
   const list=node('div');body.append(list);if(tracks.length)trackList(list,tracks);
   if(error)body.append(node('p','catalog-error',error),button('重试加载','secondary',load));
   else if(loading)body.append(node('p','loading-row','正在加载…'));
   else if(hasMore)body.append(button('加载更多歌曲','catalog-more',load));
   else if(!tracks.length)empty(list,'暂时没有可用曲目','该平台暂未提供可读取的歌曲。');
   sheet.scrollTop=top;
  }
  async function load(){if(loading)return;loading=true;draw();try{const r=await native('catalog',{operation:entity.kind,id:entity.id,offset});if(!alive())return;if(!Array.isArray(r.tracks))throw new Error('曲目响应异常');const ids=new Set(tracks.map(t=>t.uid));r.tracks.forEach(t=>{if(!ids.has(t.uid)){tracks.push(t);ids.add(t.uid);}});offset=r.offset;hasMore=!!r.hasMore;resultEntity=r.entity||entity;description=(r.description||'')+' · 已加载 '+tracks.length+' / '+r.total+' 首';notice=r.notice||'';loading=false;draw();}catch(e){if(!alive())return;loading=false;draw('加载失败：'+e.message);}}
  load();
 }
 $('search-kind').onchange=()=>setKind($('search-kind').value);
 $('keyword').addEventListener('input',()=>{if(!$('keyword').value.trim())showHome();});
 $('recommend-playlists').onclick=()=>{homeTab='recommend';loadHome();};$('recommend-hot').onclick=()=>{homeTab='hot';loadHome();};$('refresh-recommend').onclick=()=>loadHome(true);
 $('results-heading').textContent='发现音乐';loadHome();
 return{search,render,setKind,cleanOnline,onlineLibrary,openEntity,showHome};
})();
