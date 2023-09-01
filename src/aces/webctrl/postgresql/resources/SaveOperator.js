const req = new XMLHttpRequest();
req.open("POST","__PREFIX__/SaveOperator");
req.setRequestHeader("content-type", "application/x-www-form-urlencoded");
req.timeout = 20000;
req.onreadystatechange = function(){
  if (this.readyState===4){
    if (this.status===200){
      alert(this.responseText);
    }else{
      alert("An error has occurred.");
    }
  }
};
req.send("username="+encodeURIComponent("__USERNAME__")+"&refname="+encodeURIComponent("__REF_NAME__"));