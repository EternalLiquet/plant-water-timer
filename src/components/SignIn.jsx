import React from 'react';
import { Button } from '@material-ui/core';

export default function SignIn(props) {
    const signInWithGoogle = () => {
        const provider = new props.firebase.auth.GoogleAuthProvider();
        props.auth.signInWithPopup(provider);
      }
    
      return (
        <Button onClick={signInWithGoogle}>Sign In With Google</Button>
      );
}