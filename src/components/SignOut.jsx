import React from "react";
import { Button } from "@mui/material";

export default function SignOut(props) {
  const signOutWithGoogle = () => {
    props.auth.signOut();
  };

  return (
    <Button variant="outlined" onClick={signOutWithGoogle}>
      Sign Out
    </Button>
  );
}
