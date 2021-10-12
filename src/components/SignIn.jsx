import { Button } from "@material-ui/core";
import React from "react";

export default function SignIn(props) {
  const signInWithGoogle = () => {
    const provider = new props.firebase.auth.GoogleAuthProvider();
    props.auth.signInWithPopup(provider);
  };

  return <Button onClick={signInWithGoogle}>Sign In With Google</Button>;
}
