'use strict';
/** Controls the shared cover/lyrics face and the hand-off between user scrolling and follow. */
class LyricsController {
  constructor(options) {
    Object.assign(this, options);
    this.lines=[];this.index=-1;this.visible=false;this.browsing=false;
    this.touching=false;this.pointerHolding=false;this.moved=false;
    this.autoScrollUntil=0;this.lastInputAt=0;this.suppressClickUntil=0;
    this.idleMs=1500;this.resumeTimer=null;this.flipTimer=null;
    this.cover.addEventListener('click',()=>this.setVisible(true));
    this.back.addEventListener('click',()=>this.setVisible(false));
    this.toggle.addEventListener('click',()=>this.setVisible(!this.visible));
    this.box.addEventListener('scroll',()=>{
      if(!this.visible||!this.lines.length||Date.now()<this.autoScrollUntil)return;
      this.noteInput();
    },{passive:true});
    this.box.addEventListener('wheel',()=>this.noteInput(),{passive:true});
    this.box.addEventListener('touchstart',e=>{
      this.touching=true;this.moved=false;this.suppressClickUntil=0;
      const p=e.touches[0];this.startX=p.clientX;this.startY=p.clientY;
      this.noteInput(true);
    },{passive:true});
    this.box.addEventListener('touchmove',e=>{
      const p=e.touches[0];if(p&&Math.hypot(p.clientX-this.startX,p.clientY-this.startY)>8)this.moved=true;
      this.noteInput();
    },{passive:true});
    const endTouch=()=>{if(!this.touching)return;this.touching=false;if(this.moved)this.suppressClickUntil=Date.now()+350;this.noteInput();};
    window.addEventListener('touchend',endTouch,{passive:true});
    window.addEventListener('touchcancel',endTouch,{passive:true});
    this.box.addEventListener('pointerdown',e=>{
      if(e.pointerType==='touch')return;
      this.pointerHolding=true;this.moved=false;this.suppressClickUntil=0;
      this.startX=e.clientX;this.startY=e.clientY;this.noteInput(true);
    },{passive:true});
    this.box.addEventListener('pointermove',e=>{
      if(e.pointerType==='touch'||!this.pointerHolding)return;
      if(Math.hypot(e.clientX-this.startX,e.clientY-this.startY)>8)this.moved=true;
    },{passive:true});
    const endPointer=e=>{
      if(e.pointerType==='touch'||!this.pointerHolding)return;
      this.pointerHolding=false;if(this.moved)this.suppressClickUntil=Date.now()+350;this.noteInput();
    };
    window.addEventListener('pointerup',endPointer,{passive:true});
    window.addEventListener('pointercancel',endPointer,{passive:true});
    this.box.addEventListener('keydown',e=>{if(['ArrowUp','ArrowDown','PageUp','PageDown','Home','End',' '].includes(e.key))this.noteInput(true);});
    this.observer=new ResizeObserver(()=>{this.sizePadding();if(!this.browsing)this.followCurrent(false);});
    this.observer.observe(this.box);
    this.setVisible(false);
  }
  status() {
    this.statusNode.textContent=!this.lines.length?(this.plainText?'纯文本歌词 · 暂无同步时间轴':''):(this.browsing?'正在浏览 · 停止后自动跟随':'跟随当前播放'+(this.provider?' · '+this.provider:''));
    this.statusNode.classList.toggle('browsing',this.browsing);
  }
  noteInput(cancelSmooth=false) {
    if(!this.visible||!this.lines.length)return;
    this.browsing=true;this.lastInputAt=Date.now();this.autoScrollUntil=0;
    clearTimeout(this.resumeTimer);
    if(cancelSmooth)this.box.scrollTo({top:this.box.scrollTop,behavior:'auto'});
    this.status();
    if(!this.touching&&!this.pointerHolding)this.armResume();
  }
  armResume() {
    clearTimeout(this.resumeTimer);
    const remaining=Math.max(0,this.idleMs-(Date.now()-this.lastInputAt));
    this.resumeTimer=setTimeout(()=>{
      if(this.touching||this.pointerHolding)return;
      if(Date.now()-this.lastInputAt<this.idleMs){this.armResume();return;}
      this.browsing=false;this.status();this.followCurrent(true);
    },remaining+10);
  }
  setVisible(value) {
    this.visible=!!value;this.card.classList.toggle('flipped',this.visible);if(this.onVisibilityChange)this.onVisibilityChange(this.visible);
    this.cover.setAttribute('aria-expanded',String(this.visible));this.cover.tabIndex=this.visible?-1:0;
    this.face.setAttribute('aria-hidden',String(!this.visible));this.face.inert=!this.visible;
    this.toggle.textContent=this.visible?'轻点此处，翻回封面':'轻点封面，翻到歌词';
    this.toggle.setAttribute('aria-label',this.visible?'翻回专辑封面':'翻转封面，查看歌词');
    clearTimeout(this.flipTimer);
    if(this.visible){this.sizePadding();this.flipTimer=setTimeout(()=>this.followCurrent(false),440);}
    else{this.browsing=false;this.touching=false;this.pointerHolding=false;clearTimeout(this.resumeTimer);this.status();}
  }
  sizePadding() {
    const sample=this.box.querySelector('.lyric-line');
    const lineHeight=sample?(parseFloat(getComputedStyle(sample).minHeight)||sample.offsetHeight):34;
    // The latest requested behavior centers the active line; empty/plain lyrics still begin at the top.
    const edge=Math.max(0,(this.box.clientHeight-lineHeight)/2);
    this.box.style.paddingTop=this.lines.length?edge+'px':'4px';
    this.box.style.paddingBottom=this.lines.length?edge+'px':'12px';
  }
  renderSearchActions() {
    const controls=document.createElement('div');controls.className='lyrics-search-actions';
    this.searchButton=document.createElement('button');this.searchButton.className='lyrics-search-button';this.searchButton.type='button';
    this.searchButton.addEventListener('click',()=>this.searchState==='choices'?this.onChoices():this.onSearch());
    const edit=document.createElement('button');this.editButton=edit;edit.type='button';edit.className='lyrics-search-edit';edit.textContent='修改歌名 / 歌手';edit.addEventListener('click',()=>this.onEdit());
    this.searchMessage=document.createElement('p');this.searchMessage.className='lyrics-search-message';this.searchMessage.setAttribute('role','status');
    controls.append(this.searchButton,edit,this.searchMessage);this.box.append(controls);
  }
  setNetworkState(state,message,provider='') {
    this.searchState=state;this.networkMessage=message;this.provider=provider;this.status();
    if(!this.searchButton||!this.searchButton.isConnected)return;
    this.searchButton.disabled=state==='searching';if(this.editButton)this.editButton.disabled=state==='searching';
    this.searchButton.textContent=state==='searching'?'正在联网匹配歌词…':state==='choices'?'查看匹配结果':state==='notfound'||state==='error'?'重新联网搜索歌词':'联网搜索歌词';
    this.searchMessage.textContent=message||'';
  }
  setLines(lines,emptyText,plainText='') {
    this.lines=lines;this.plainText=plainText;this.index=-1;this.browsing=false;this.touching=false;this.pointerHolding=false;
    clearTimeout(this.resumeTimer);this.autoScrollUntil=Date.now()+700;this.status();this.box.replaceChildren();
    if(!lines.length){const p=document.createElement('p');p.className='muted';p.textContent=plainText?'已找到文本歌词，暂无同步时间轴。':emptyText;this.box.append(p);this.renderSearchActions();if(plainText){const text=document.createElement('div');text.className='plain-lyrics';text.textContent=plainText;this.box.append(text);}this.sizePadding();this.setNetworkState(this.searchState,this.networkMessage||'',this.provider||'');return;}
    lines.forEach((line,i)=>{
      const button=document.createElement('button');button.type='button';button.className='lyric-line';button.textContent=line.text;
      button.setAttribute('aria-label','从这句开始播放：'+line.text);
      button.addEventListener('click',event=>{
        if(event.detail!==0&&(this.moved||Date.now()<this.suppressClickUntil))return;
        this.noteInput(true);this.onSeek(line.time);
      });
      this.box.append(button);
    });
    this.sizePadding();
  }
  setCurrentIndex(index) {
    if(index===this.index)return;
    this.index=index;
    [...this.box.querySelectorAll('.lyric-line')].forEach((line,i)=>{
      line.classList.toggle('active',i===index);if(i===index)line.setAttribute('aria-current','true');else line.removeAttribute('aria-current');
    });
    this.followCurrent(true);
  }
  followCurrent(smooth=true) {
    if(!this.visible||this.browsing||!this.canFollow()||this.index<0)return;
    const line=this.box.querySelectorAll('.lyric-line')[this.index];if(!line)return;
    const top=Math.max(0,line.offsetTop+(line.offsetHeight-this.box.clientHeight)/2);
    if(Math.abs(this.box.scrollTop-top)<1)return;
    this.autoScrollUntil=Date.now()+700;
    this.box.scrollTo({top,behavior:smooth?'smooth':'auto'});
  }
}
window.LyricsController=LyricsController;
