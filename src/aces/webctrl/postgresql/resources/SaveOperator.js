const req = new XMLHttpRequest();
req.open("POST","__PREFIX__/SaveOperator");
req.setRequestHeader("content-type", "application/x-www-form-urlencoded");
req.timeout = 10000;
req.onreadystatechange = function(){
  if (this.readyState===4){
    if (this.status===200){
      alert(this.responseText);
    }else if (this.status==0){
      alert("Request timed out.");
    }else{
      alert("An error has occurred.");
    }
  }
};
req.send("username="+encodeURIComponent("__USERNAME__")+"&refname="+encodeURIComponent("__REF_NAME__"));