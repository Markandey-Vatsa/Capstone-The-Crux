// Single fixed theme (previous toggle removed). Keep fade overlay for intro aesthetic.
(function(){
	function createIntroOverlay(){
		const overlay=document.createElement('div');
		overlay.className='theme-fade-overlay';
		document.body.appendChild(overlay);
		requestAnimationFrame(()=>overlay.classList.add('fade-out'));
		setTimeout(()=>overlay.remove(),900);
	}
	document.addEventListener('DOMContentLoaded',createIntroOverlay);
})();
